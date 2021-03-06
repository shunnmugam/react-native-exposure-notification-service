package ie.gov.tracing.nearby;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.threeten.bp.Duration;

import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.TaskToFutureAdapter;
import ie.gov.tracing.network.DiagnosisKeyDownloader;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.storage.ExposureNotificationRepository;
import ie.gov.tracing.storage.SharedPrefs;
import ie.gov.tracing.storage.TokenEntity;

public class ProvideDiagnosisKeysWorker extends ListenableWorker {
  static final Duration DEFAULT_API_TIMEOUT = Duration.ofSeconds(15);

  private static final String WORKER_NAME = "ProvideDiagnosisKeysWorker";
  private static final BaseEncoding BASE64_LOWER = BaseEncoding.base64();
  private static final int RANDOM_TOKEN_BYTE_LENGTH = 32;

  private final DiagnosisKeyDownloader diagnosisKeys;
  private final DiagnosisKeyFileSubmitter submitter;
  private final SecureRandom secureRandom;
  private final ExposureNotificationRepository repository;
  public static long nextSince = 0;

  public ProvideDiagnosisKeysWorker(@NonNull Context context,
                                    @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    diagnosisKeys = new DiagnosisKeyDownloader(context);
    submitter = new DiagnosisKeyFileSubmitter(context);
    secureRandom = new SecureRandom();
    repository = new ExposureNotificationRepository(context);
  }

  private String generateRandomToken() {
    byte[] bytes = new byte[RANDOM_TOKEN_BYTE_LENGTH];
    secureRandom.nextBytes(bytes);
    return BASE64_LOWER.encode(bytes);
  }

  private void deleteOldData() {
    try {
      long DAY_IN_MS = 1000 * 60 * 60 * 24;
      long storeExposuresFor = SharedPrefs.getLong("storeExposuresFor", this.getApplicationContext());
      long daysBeforeNowInMs = System.currentTimeMillis() - storeExposuresFor * DAY_IN_MS;
      Events.raiseEvent(Events.INFO, "deleteOldData - delete exposures/tokens before: " +
              new Date(daysBeforeNowInMs));
      repository.deleteExposuresBefore(daysBeforeNowInMs);
      repository.deleteTokensBefore(daysBeforeNowInMs);
    } catch(Exception ex) {
      Events.raiseError("deleteOldData",  ex);
    }
  }

  private void updateLastRun() {
    try {
      String [] lastRun = SharedPrefs.getString("lastRun", Tracing.currentContext).split("\\s*,\\s*");

      List<String> lastRuns = new ArrayList<>(Arrays.asList(lastRun));

      lastRuns.add("" + System.currentTimeMillis());

      int firstIndex = lastRuns.size() - 10;
      if (firstIndex < 0) firstIndex = 0;
      String newLastRun = lastRuns.get(firstIndex);
      for(int i = firstIndex + 1; i < lastRuns.size(); i++) {
        newLastRun += "," + lastRuns.get(i).trim();
      }

      SharedPrefs.setString("lastRun", newLastRun, Tracing.currentContext);
    } catch(Exception ex) {
      Events.raiseError("lastRun",  ex);
    }
  }

  @NonNull
  @Override
  public ListenableFuture<Result> startWork() {
      Tracing.currentContext = getApplicationContext();
      Events.raiseEvent(Events.INFO, "ProvideDiagnosisKeysWorker.startWork");

      updateLastRun();
      SharedPrefs.remove("lastApiError", Tracing.currentContext);
      SharedPrefs.remove("lastError", Tracing.currentContext);

      deleteOldData();

      final String token = generateRandomToken();
      return FluentFuture.from(TaskToFutureAdapter
              .getFutureWithTimeout(
                      ExposureNotificationClientWrapper.get(getApplicationContext()).isEnabled(),
                      DEFAULT_API_TIMEOUT.toMillis(),
                      TimeUnit.MILLISECONDS,
                      AppExecutors.getScheduledExecutor()))
              .transformAsync(isEnabled -> {
                // Only continue if it is enabled.
                if (isEnabled != null && isEnabled) {
                  return diagnosisKeys.download();
                } else {
                  // Stop here because things aren't enabled. Will still return successful though.
                  return Futures.immediateFailedFuture(new NotEnabledException());
                }
              },
                      AppExecutors.getBackgroundExecutor())
              .transformAsync(files -> submitter.parseFiles(files, token),
                      AppExecutors.getBackgroundExecutor())
              .transformAsync(done -> repository.upsertTokenEntityAsync(TokenEntity.create(token, false)),
                      AppExecutors.getBackgroundExecutor())
              .transform(done -> processSuccess(), // all done, do tidy ups here
                      AppExecutors.getLightweightExecutor())
              .catching(NotEnabledException.class,
                      ex -> Result.success(), // not enabled, just return success
                      AppExecutors.getBackgroundExecutor())
              .catching(Exception.class, this::processFailure,
                      AppExecutors.getBackgroundExecutor());
  }

  private Result processFailure(Exception ex) {
    Events.raiseError("error processing file: ",  ex);
    return Result.failure();
  }

  private void deleteExports() {
    try {
      File directory = new File(this.getApplicationContext().getFilesDir() + "/diag_keys/");
      File[] files = directory.listFiles();
      Events.raiseEvent(Events.INFO, "deleteExports - files to delete: " + files.length);
      for (File file : files) {
        try {
          if (file.delete()) {
            Events.raiseEvent(Events.INFO, "deleteExports - deleted file:" + file.getName());
          } else {
            Events.raiseEvent(Events.INFO, "deleteExports - file not deleted:" + file.getName());
          }
        } catch (Exception ex) {
          Events.raiseError("deleteExports - error deleting file: " + file.getName(), ex);
        }
      }
    } catch(Exception ex) {
      Events.raiseError("deleteExports - error deleting files", ex);
    }
  }

  private void saveDailyMetric() {
    try {
      long dailyActiveTrace = SharedPrefs.getLong("dailyActiveTrace", this.getApplicationContext());

      Events.raiseEvent(Events.INFO, "saveDailyMetric - last DAILY_ACTIVE_TRACE: " + dailyActiveTrace);

      Calendar cal1 = Calendar.getInstance();
      cal1.setTime(new Date(dailyActiveTrace));

      Calendar cal2 = Calendar.getInstance(); // now

      boolean sameDay = cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR) &&
              cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR);

      if(dailyActiveTrace > 0 && sameDay) {
        Events.raiseEvent(Events.INFO, "saveDailyMetric - already sent today");
        return;
      }

      Events.raiseEvent(Events.INFO, "saveDailyMetric - saving DAILY_ACTIVE_TRACE metric");
      Fetcher.saveMetric("DAILY_ACTIVE_TRACE", this.getApplicationContext(), null);
      SharedPrefs.setLong("dailyActiveTrace", System.currentTimeMillis(), this.getApplicationContext());

    } catch(Exception ex) {
      Events.raiseError("saveDailyMetric - error", ex);
    }
  }

  private Result processSuccess() {
    if(nextSince > 0) {
      Events.raiseEvent(Events.INFO, "success processing exports, setting since index to: " + nextSince);
      SharedPrefs.setLong("since", nextSince, Tracing.currentContext);

      // try delete, does not affect success
      deleteExports();
    }

    // try save daily metric, does not affect success
    saveDailyMetric();

    return Result.success();
  }

  private static boolean isWorkScheduled() {
    WorkManager instance = WorkManager.getInstance(Tracing.context);
    ListenableFuture<List<WorkInfo>> statuses = instance.getWorkInfosByTag(WORKER_NAME);
    try {
      List<WorkInfo> workInfoList = statuses.get();
      for (WorkInfo workInfo : workInfoList) {
        if(workInfo.getState() == WorkInfo.State.RUNNING || workInfo.getState() == WorkInfo.State.ENQUEUED)
          return true;
      }
    } catch (Exception ex) {
      Events.raiseError("isWorkScheduled",  ex);
    }
    return false;
  }

  public static void startScheduler() {
    /*if(isWorkScheduled()) {
      Events.raiseEvent(Events.INFO, "ProvideDiagnosisKeysWorker.startScheduler: already scheduled");
      return;
    }*/

    long checkFrequency = SharedPrefs.getLong("exposureCheckFrequency", Tracing.context);
    Events.raiseEvent(Events.INFO, "ProvideDiagnosisKeysWorker.startScheduler: run every " +
            checkFrequency + " minutes");
    WorkManager workManager = WorkManager.getInstance(Tracing.context);
    PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            ProvideDiagnosisKeysWorker.class, checkFrequency, TimeUnit.MINUTES)
            .setInitialDelay(30, TimeUnit.SECONDS) // could offset this, but idle may be good enough
            .addTag(WORKER_NAME)
            .setConstraints(
                    new Constraints.Builder()
                            //.setRequiresBatteryNotLow(true)
                            //.setRequiresDeviceIdle(true)
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
            .build();
    workManager
            .enqueueUniquePeriodicWork(WORKER_NAME, ExistingPeriodicWorkPolicy.REPLACE, workRequest);
  }


  public static void startOneTimeWorkRequest() {
    Events.raiseEvent(Events.INFO, "ProvideDiagnosisKeysWorker.startOneTimeWorker");
    WorkManager workManager = WorkManager.getInstance(Tracing.context);
    OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(ProvideDiagnosisKeysWorker.class)
            .setConstraints(
                    new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
            .build();
    workManager.enqueueUniqueWork("OneTimeWorker", ExistingWorkPolicy.REPLACE, workRequest);
  }

  public static void stopScheduler() {
    Events.raiseEvent(Events.INFO, "ProvideDiagnosisKeysWorker.stopScheduler");
    WorkManager.getInstance(Tracing.context).cancelAllWorkByTag(WORKER_NAME);
  }

  private static class NotEnabledException extends Exception {}
}