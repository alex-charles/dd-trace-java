package datadog.trace.core.scopemanager;

import com.google.common.util.concurrent.RateLimiter;
import com.timgroup.statsd.StatsDClient;
import datadog.trace.api.Config;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator;
import datadog.trace.mlt.MethodLevelTracer;
import datadog.trace.mlt.Session;
import datadog.trace.mlt.SessionData;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public abstract class TraceProfilingScopeInterceptor
    extends ScopeInterceptor.DelegatingInterceptor {
  private static final long MAX_NANOSECONDS_BETWEEN_ACTIVATIONS = TimeUnit.SECONDS.toNanos(1);
  private static final double ACTIVATIONS_PER_SECOND = 5;
  private static final ThreadLocal<Boolean> IS_THREAD_PROFILING =
      new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
          return false;
        }
      };

  private final StatsDClient statsDClient;

  protected final RateLimiter rateLimiter = RateLimiter.create(ACTIVATIONS_PER_SECOND);

  private TraceProfilingScopeInterceptor(
      final StatsDClient statsDClient, final ScopeInterceptor delegate) {
    super(delegate);
    this.statsDClient = statsDClient;
  }

  public static ScopeInterceptor create(
      final Double methodTraceSampleRate,
      final TraceHeuristicsEvaluator traceHeuristicsEvaluator,
      final StatsDClient statsDClient,
      final ScopeInterceptor delegate) {
    if (!Config.get().isMethodTraceEnabled()) {
      return delegate;
    }
    if (methodTraceSampleRate != null) {
      return new Percentage(methodTraceSampleRate, statsDClient, delegate);
    }
    return new Heuristical(traceHeuristicsEvaluator, statsDClient, delegate);
  }

  @Override
  public Scope handleSpan(final AgentSpan span) {
    if (!(span instanceof AgentTracer.NoopAgentSpan)
        && (IS_THREAD_PROFILING.get() // if already profiling, we want to add more context.
            || shouldProfile(span))) {
      return new TraceProfilingScope(span, delegate.handleSpan(span));
    }
    // We don't want to wrap the scope for profiling.
    return delegate.handleSpan(span);
  }

  abstract boolean shouldProfile(AgentSpan span);

  private static class Percentage extends TraceProfilingScopeInterceptor {
    private static final BigDecimal TRACE_ID_MAX_AS_BIG_DECIMAL =
        new BigDecimal(CoreTracer.TRACE_ID_MAX);

    private final long cutoff;

    private Percentage(
        final double percent, final StatsDClient statsDClient, final ScopeInterceptor delegate) {
      super(statsDClient, delegate);
      assert 0 <= percent && percent <= 1;
      cutoff = new BigDecimal(percent).multiply(TRACE_ID_MAX_AS_BIG_DECIMAL).longValue();
    }

    @Override
    boolean shouldProfile(final AgentSpan span) {
      // Do we want to apply rate limiting?
      return compareUnsigned(span.getTraceId().toLong(), cutoff) <= 0;
    }

    // When we drop support for 1.7, we can use Long.compareUnsigned directly.
    public static int compareUnsigned(final long x, final long y) {
      return Long.compare(x + Long.MIN_VALUE, y + Long.MIN_VALUE);
    }
  }

  private static class Heuristical extends TraceProfilingScopeInterceptor {
    private volatile long lastProfileTimestamp = System.nanoTime();

    private final TraceHeuristicsEvaluator traceEvaluator;

    private Heuristical(
        final TraceHeuristicsEvaluator traceEvaluator,
        final StatsDClient statsDClient,
        final ScopeInterceptor delegate) {
      super(statsDClient, delegate);
      this.traceEvaluator = traceEvaluator;
    }

    @Override
    boolean shouldProfile(final AgentSpan span) {
      if (maybeInteresting(span) && acquireProfilePermit()) {
        lastProfileTimestamp = System.nanoTime();
        return true;
      }
      return false;
    }

    private boolean maybeInteresting(final AgentSpan span) {
      if (traceEvaluator.isDistinctive(span.getLocalRootSpan())) {
        // This is a distinctive trace, so lets profile it.
        return true;
      }

      Integer samplingPriority = span.getSamplingPriority();
      if (samplingPriority != null && samplingPriority == PrioritySampling.USER_KEEP) {
        // The trace was manually identified as important, so more likely interesting.
        return true;
      }

      if (lastProfileTimestamp + MAX_NANOSECONDS_BETWEEN_ACTIVATIONS < System.nanoTime()) {
        // It's been a while since we last profiled, so lets take one now.
        // Due to the multi-threaded nature here, we will likely have multiple threads enter here
        // but they will still be subject to the rate limiter following.
        return true;
      }
      return false;
    }

    private boolean acquireProfilePermit() {
      return rateLimiter.tryAcquire();
    }
  }

  private static final ThreadLocal<Integer> SCOPE_LEVEL = new ThreadLocal<Integer>() {
    @Override
    protected Integer initialValue() {
      return 0;
    }
  };

  private static final ThreadLocal<Deque<AtomicLong>> CUMULATIVE_SAMPLES = new ThreadLocal<Deque<AtomicLong>>() {
    @Override
    protected Deque<AtomicLong> initialValue() {
      return new ArrayDeque<>();
    }
  };

  private class TraceProfilingScope extends DelegatingScope {
    private final long timestamp;

    private final Session session;
    private final boolean rootScope;

    private TraceProfilingScope(final AgentSpan span, final Scope delegate) {
      super(delegate);
      CUMULATIVE_SAMPLES.get().push(new AtomicLong(0L));
      SCOPE_LEVEL.set(SCOPE_LEVEL.get() + 1);
      timestamp = System.nanoTime();
      rootScope = !IS_THREAD_PROFILING.get();
      if (rootScope) {
        log.info("Open scope: {}, {}", Thread.currentThread().getId(), span.getTraceId());
        statsDClient.incrementCounter("mlt.scope", "scope:root");
        IS_THREAD_PROFILING.set(true);
      } else {
        statsDClient.incrementCounter("mlt.scope", "scope:child");
      }
      session = MethodLevelTracer.startProfiling(span.getTraceId().toString());
    }

    @Override
    public void close() {
      long duration = TimeUnit.MILLISECONDS.convert(System.nanoTime() - timestamp, TimeUnit.NANOSECONDS);
      int myLevel = SCOPE_LEVEL.get();
      SCOPE_LEVEL.set(Math.max(myLevel - 1, 0));
      if (rootScope) {
        IS_THREAD_PROFILING.set(false);
      }
      delegate.close();
      final SessionData samplingData = session.close();

      long samples = samplingData.getSampleCount() + CUMULATIVE_SAMPLES.get().pop().get();
      if (!rootScope) {
        CUMULATIVE_SAMPLES.get().peek().addAndGet(samples);
      }
      if (duration > 0) {
        log.info("Scope close: {},{},{},{},{},{}", span().getSpanName(), span().getTraceId().toLong(), rootScope, duration, Thread.currentThread().getId(), samples);
      }
      byte[] data = samplingData.getBlob();
      if (data != null) {
        statsDClient.incrementCounter("mlt.count");
        statsDClient.count("mlt.samples", samplingData.getSampleCount());
        statsDClient.count("mlt.bytes", samplingData.getBlob().length);
        AgentSpan span = span();
        span.setTag(InstrumentationTags.DD_MLT, data);
        if (span.getSamplingPriority() == null) {
          // if priority not set, let's increase priority to improve chance this is kept.
          span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
        }
      }
    }
  }
}
