package com.fixmycity.common;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;

/**
 * Sliding-window limit per key (a user id, or an email for failed sign-ins).
 * ponytail: in memory for one backend instance; move to a shared store to scale out.
 */
public final class RateLimit {

	/** Past this many keys, idle ones are dropped so arbitrary keys cannot grow the map without bound. */
	private static final int SWEEP_THRESHOLD = 10_000;

	private final int limit;

	private final Duration window;

	private final String message;

	private final Map<Object, Deque<Instant>> calls = new ConcurrentHashMap<>();

	public RateLimit(int limit, Duration window, String message) {
		this.limit = limit;
		this.window = window;
		this.message = message;
	}

	/** Counts one call for the key, or rejects it with 429 when the window is full. */
	public void check(Object key) {
		Deque<Instant> recent = recent(key);
		synchronized (recent) {
			requireCapacity(recent);
			recent.addLast(Instant.now());
		}
	}

	/** Rejects with 429 when the window is full, without counting this call; count with {@link #record}. */
	public void requireCapacity(Object key) {
		Deque<Instant> recent = recent(key);
		synchronized (recent) {
			requireCapacity(recent);
		}
	}

	public void record(Object key) {
		Deque<Instant> recent = recent(key);
		synchronized (recent) {
			recent.addLast(Instant.now());
		}
	}

	private Deque<Instant> recent(Object key) {
		if (this.calls.size() > SWEEP_THRESHOLD) {
			Instant cutoff = Instant.now().minus(this.window);
			this.calls.values().removeIf((recent) -> {
				synchronized (recent) {
					return recent.isEmpty() || recent.peekLast().isBefore(cutoff);
				}
			});
		}
		return this.calls.computeIfAbsent(key, (k) -> new ArrayDeque<>());
	}

	private void requireCapacity(Deque<Instant> recent) {
		Instant cutoff = Instant.now().minus(this.window);
		while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
			recent.pollFirst();
		}
		if (recent.size() >= this.limit) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, this.message);
		}
	}

}
