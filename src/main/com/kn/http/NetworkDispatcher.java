package com.kn.http;

import com.kn.http.HttpClient.Request;
import com.kn.http.HttpClient.Response;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author nk
 */

public final class NetworkDispatcher {
  final HttpClient httpClient;
  // TODO does not work properly when request's size become more than LinkedBlockingQueue's size
  private final ExecutorService service =
      new ThreadPoolExecutor(0, 6, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(6),
          new ThreadFactory() {
            private final AtomicInteger poolNumber = new AtomicInteger(1);

            @Override public Thread newThread(Runnable runnable) {
              return new Thread(runnable,
                  "network-dispatcher-thread-" + poolNumber.getAndIncrement());
            }
          });

  public NetworkDispatcher(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public Cancelable execute(final Request request, Callback<Response> callback) {
    WeakReference<Callback<Response>> weakReference = new WeakReference<>(callback);

    CancelableTask task = createRunnable(request, weakReference);
    task.future = service.submit(task);
    return task;
  }

  private final CancelableTask createRunnable(final Request request,
      final WeakReference<Callback<Response>> callback) {
    return new CancelableTask() {
      HttpClient.Call call;

      @Override public void run() {
        if (isCanceled) return;

        try {
          call = httpClient.call(request);
          success(call.execute());
        } catch (IOException e) {
          failure(e);
        }
      }

      @Override
      public void cancel() {
        super.cancel();
        if (!call.isExecuted()) {
          call.cancel();
        }
      }

      private void success(Response response) {
        if (!isCanceled && callback.get() != null) {
          try {
            callback.get().onSuccess(response);
          } catch (Exception e) {
            failure(e);
          }
        }
      }

      private void failure(Exception e) {
        if (!isCanceled && callback.get() != null) {
          callback.get().onFailure(e);
        }
      }
    };
  }

  /** Cancelable runnable */
  abstract class CancelableTask implements Runnable, Cancelable {
    volatile boolean isCanceled; //https://stackoverflow.com/a/3787435/1934509
    Future future;

    @Override
    public void cancel() {
      isCanceled = true;
      future.cancel(false);
    }
  }

  /** Callback for response */
  public interface Callback<Response> {
    void onSuccess(Response response);

    void onFailure(Exception e);
  }

  public interface Cancelable {
    void cancel();
  }

}
