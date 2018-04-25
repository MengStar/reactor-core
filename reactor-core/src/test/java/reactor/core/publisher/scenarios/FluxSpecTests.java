/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher.scenarios;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.BalancedFluxProcessor;
import reactor.core.publisher.BalancedMonoProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Processors;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.subscriber.AssertSubscriber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class FluxSpecTests {

	@Test
	public void fluxInitialValueAvailableOnceIfBroadcasted() {
//		"A deferred Flux with an initial value makes that value available once if broadcasted"
//		given: "a composable with an initial value"
		Flux<String> stream = Flux.just("test")
		                          .publish()
		                          .autoConnect();

//		when: "the value is retrieved"
		AtomicReference<String> value = new AtomicReference<>();
		AtomicReference<String> value2 = new AtomicReference<>();
		stream.subscribe(value::set);
		stream.subscribe(value2::set);

//		then: "it is available in value 1 but value 2 has subscribed after dispatching"
		assertThat(value.get()).isEqualTo("test");
		assertThat(value2.get()).isNullOrEmpty();
	}

	@Test
	public void deferredFluxInitialValueLaterAvailableUpToLongMax() throws InterruptedException {
//		"A deferred Flux with an initial value makes that value available later up to Long.MAX "
//		given: "a composable with an initial value"
		AtomicReference<Throwable> e = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		Flux<Integer> stream = Flux.fromIterable(Arrays.asList(1, 2, 3))
		                           .publish()
		                           .autoConnect()
		                           .doOnError(e::set)
		                           .doOnComplete(latch::countDown);

//		when: "cumulated request of Long MAX"
		long test = Long.MAX_VALUE / 2L;
		AssertSubscriber<Integer> controls =
				stream.subscribeWith(AssertSubscriber.create(0));
		controls.request(test);
		controls.request(test);
		controls.request(1);

		//sleep(2000)

//		then: "no error available"
		latch.await(2, TimeUnit.SECONDS);

		assertThat(e.get()).isNull();
	}

	@Test
	public void fluxInitialValueCanBeConsumedMultipleTimes() {
//	    "A deferred Flux with initial values can be consumed multiple times"
// 		given: "a composable with an initial value"
		Flux<String> stream = Flux.just("test", "test2", "test3")
		                          .map(v -> v)
		                          .log();

//		when: "the value is retrieved"
		List<String> value1 = stream.collectList().block();
		List<String> value2 = stream.collectList().block();

//		then: "it is available"
		assertThat(value1).containsExactlyElementsOf(value2);
	}

	@Test
	public void fluxCanFilterTerminalStates() {
//		"A deferred Flux can filter terminal states"
//		given: "a composable with an initial value"
		Flux<String> stream = Flux.just("test");

//		when:"the complete signal is observed and flux is retrieved"
		Mono<Void> tap = stream.then();

//		then: "it is available"
		assertThat(tap.block()).isNull();

//		when: "the error signal is observed and flux is retrieved"
		stream = Flux.error(new Exception());
		final Mono<Void> errorTap = stream.then();

//		then: "it is available"
		assertThatExceptionOfType(Exception.class)
				.isThrownBy(errorTap::block);
	}

	@Test
	public void fluxCanListenForTerminalStates() {
//	"A deferred Flux can listen for terminal states"
//		given: "a composable with an initial value"
		Flux<String> stream = Flux.just("test");

//		when: "the complete signal is observed and flux is retrieved"
		AtomicReference<Object> value = new AtomicReference<>();

		stream.doAfterTerminate(() -> value.set(Boolean.TRUE))
	          .subscribe(value::set);

//		then: "it is available"
		assertThat(value.get())
				.isNotNull()
	            .isNotEqualTo("test")
	            .isEqualTo(Boolean.TRUE);
	}

	@Test
	public void fluxCanBeTranslatedToList() {
//		"A deferred Flux can be translated into a list"
//		given: "a composable with an initial value"
		Flux<String> stream = Flux.just("test", "test2", "test3");

//		when:"the flux is retrieved"
		Mono<List<String>> value = stream.map(it -> it + "-ok")
		                                 .collectList();

//		then: "it is available"
		assertThat(value.block()).containsExactly("test-ok", "test2-ok", "test3-ok");
	}

	@Test
	public void fluxCanBeTranslatedToCompletableQueue() {
//		"A deferred Flux can be translated into a completable queue"
//		given:	"a composable with an initial value"
		Flux<String> stream = Flux.just("test", "test2", "test3")
		                    .log()
		                    .publishOn(Schedulers.parallel());

//		when: "the flux is retrieved"
		stream = stream.map(it -> it + "-ok")
		               .log();

		Iterator<String> queue = stream.toIterable()
		                               .iterator();

		List<String> result = new ArrayList<>();

		while (queue.hasNext()) {
			result.add(queue.next());
		}

//		then:"it is available"
		assertThat(result).containsExactly("test-ok", "test2-ok", "test3-ok");
	}

	@Test
	public void readQueuesFromPublishers() {
//		"Read Queues from Publishers"
//		given: "Iterable publisher of 1000 to read queue"
		List<Integer> thousand = new ArrayList<>(1000);
		for (int i = 1; i <= 1000; i++) {
			thousand.add(i);
		}
		Flux<Integer> pub = Flux.fromIterable(thousand);
		Iterator<Integer> queue = pub.toIterable()
		                             .iterator();

//		when: "read the queue"
		Integer v = queue.next();
		Integer v2 = queue.next();
		for (int i = 0; i < 997; i++) {
			queue.next();
		}

		Integer v3 = queue.next();

//		then: "queues values correct"
		assertThat(v).isEqualTo(1);
		assertThat(v2).isEqualTo(2);
		assertThat(v3).isEqualTo(1000);
	}

	Flux<Integer> scenario_rangeTimedSample() {
		return Flux.range(1, Integer.MAX_VALUE)
		           .delayElements(Duration.ofMillis(100))
		           .sample(Duration.ofSeconds(4))
		           .take(1);
	}

	@Test
	public void fluxCanSampleValuesOverTime() {
		StepVerifier.withVirtualTime(this::scenario_rangeTimedSample)
		            .thenAwait(Duration.ofSeconds(4))
		            .expectNext(39)
		            .verifyComplete();
	}

	Flux<Integer> scenario_rangeTimedTake() {
		return Flux.range(1, Integer.MAX_VALUE)
		           .delayElements(Duration.ofMillis(100))
		           .take(Duration.ofSeconds(4))
		           .takeLast(1);
	}

	@Test
	public void fluxCanSampleValuesOverTimeTake() {
		StepVerifier.withVirtualTime(this::scenario_rangeTimedTake)
		            .thenAwait(Duration.ofSeconds(4))
		            .expectNext(39)
		            .verifyComplete();
	}

	@Test
	public void fluxCanBeEnforcedToDispatchValuesDistinctFromPredecessors() {
//		"A Flux can be enforced to dispatch values distinct from their immediate predecessors"
//		given:"a composable with values 1 to 3 with duplicates"
		Flux<Integer> s = Flux.fromIterable(Arrays.asList(1, 1, 2, 2, 3));

//		when:"the values are filtered and result is collected"
		BalancedMonoProcessor<List<Integer>> tap = s.distinctUntilChanged()
		                                            .collectList()
		                                            .toProcessor();
		tap.asMono().subscribe();

//		then:"collected must remove duplicates"
		assertThat(tap.asMono().block()).containsExactly(1, 2, 3);
	}

	@Test
	public void fluxCanBeEnforcedToDispatchValuesWithKeysDistinctFromPredecessors() {
//		"A Flux can be enforced to dispatch values with keys distinct from their immediate predecessors keys"
//		given:"a composable with values 1 to 5 with duplicate keys"
		Flux<Integer> s = Flux.fromIterable(Arrays.asList(2, 4, 3, 5, 2, 5));

//		when:"the values are filtered and result is collected"
		BalancedMonoProcessor<List<Integer>> tap = s.distinctUntilChanged(it -> it % 2 == 0)
		                                            .collectList()
		                                            .toProcessor();

//		then:"collected must remove duplicates"
		assertThat(tap.asMono().block()).containsExactly(2, 3, 2, 5);
	}

	@Test
	public void fluxCanBeEnforcedToDispatchDistinctValues() {
//		"A Flux can be enforced to dispatch distinct values"
//		given:"a composable with values 1 to 4 with duplicates"
		Flux<Integer> s = Flux.fromIterable(Arrays.asList(1, 2, 3, 1, 2, 3, 4));

//		when:"the values are filtered and result is collected"
		BalancedMonoProcessor<List<Integer>> tap = s.distinct()
		                                    .collectList()
		                                    .toProcessor();
		tap.asMono().subscribe();

//		then:"collected should be without duplicates"
		assertThat(tap.asMono().block()).containsExactly(1, 2, 3, 4);
	}

	@Test
	public void fluxCanBeEnforcedToDispatchValuesHavingDistinctKeys() {
//		"A Flux can be enforced to dispatch values having distinct keys"
//		given: "a composable with values 1 to 4 with duplicate keys"
		Flux<Integer> s = Flux.fromIterable(Arrays.asList(1, 2, 3, 1, 2, 3, 4));

//		when: "the values are filtered and result is collected"
		BalancedMonoProcessor<List<Integer>> tap = s.distinct(it -> it % 3)
		                                    .collectList()
		                                    .toProcessor();
		tap.asMono().subscribe();

//		then: "collected should be without duplicates"
		assertThat(tap.asMono().block()).containsExactly(1, 2, 3);
	}

	@Test
	public void fluxCanCheckForValueSatisfyingPredicate() {
//		"A Flux can check if there is a value satisfying a predicate"
//		given: "a composable with values 1 to 5"
		Flux<Integer> s = Flux.fromIterable(Arrays.asList(1, 2, 3, 4, 5));

//		when: "checking for existence of values > 2 and the result of the check is collected"
		boolean tap = s.any(it -> it > 2)
		               .log()
		               .block();

//		then: "collected should be true"
		assertThat(tap).isTrue();


//		when: "checking for existence of values > 5 and the result of the check is collected"
		tap = s.any(it -> it > 5).block();

//		then: "collected should be false"
		assertThat(tap).isFalse();


//		when: "checking always true predicate on empty flux and collecting the result"
		tap = Flux.empty().any(it -> true).block();

//		then: "collected should be false"
		assertThat(tap).isFalse();
	}

	@Test
	public void fluxInitialValuesArePassedToConsumers() {
//		"A Flux"s initial values are passed to consumers"
//		given: "a composable with values 1 to 5 inclusive"
		Flux<Integer> stream = Flux.fromIterable(Arrays.asList(1, 2, 3, 4, 5));

//		when: "a Consumer is registered"
		List<Integer> values = new ArrayList<>();
		stream.subscribe(values::add);

//		then: "the initial values are passed"
		assertThat(values).containsExactly(1, 2, 3, 4, 5);
	}

	@Test
	public void streamStateRelatedSignalsCanBeConsumed() {
//		"Stream "state" related signals can be consumed"
//		given: "a composable with values 1 to 5 inclusive"
		Flux<Integer> stream = Flux.fromIterable(Arrays.asList(1, 2, 3, 4, 5));
		List<Integer> values = new ArrayList<>();
		List<String> signals = new ArrayList<>();

//		when: "a Subscribe Consumer is registered"
		stream = stream.doOnSubscribe(s -> signals.add("subscribe"));

//		and: "a Cancel Consumer is registered"
		stream = stream.doOnCancel(() -> signals.add("cancel"));

//		and: "a Complete Consumer is registered"
		stream = stream.doOnComplete(() -> signals.add("complete"));

//		and: "the flux is consumed"
		stream.subscribe(values::add);

//		then: "the initial values are passed"
		assertThat(values).containsExactly(1, 2, 3, 4, 5);
		assertThat(signals).containsExactly("subscribe", "complete");
	}

	@Test
	public void streamCanEmitDefaultValueIfEmpty() {
//		"Stream can emit a default value if empty"
//		given: "a composable that only completes"
		Flux<String> stream = Flux.empty();
		List<String> values = new ArrayList<>();

//		when: "a Subscribe Consumer is registered"
		stream = stream.defaultIfEmpty("test")
		               .doOnComplete(() -> values.add("complete"));

//		and: "the flux is consumed"
		stream.subscribe(values::add);

//		then: "the initial values are passed"
		assertThat(values).containsExactly("test", "complete");
	}

	@Test
	public void acceptedValuesArePassedToRegisteredConsumer() {
//		"Accepted values are passed to a registered Consumer"
//		given: "a composable with a registered consumer"
		BalancedFluxProcessor<Integer> composable = Processors.emitter().build();
		AtomicReference<Integer> value = new AtomicReference<>();

		composable.asFlux().subscribe(value::set);

//		when: "a value is accepted"
		composable.onNext(1);

//		then: "it is passed to the consumer"
		assertThat(value.get()).isEqualTo(1);

//		when: "another value is accepted"
		composable.onNext(2);

//		then: "it too is passed to the consumer"
		assertThat(value.get()).isEqualTo(2);
	}

	@Test
	public void acceptedErrorsArePassedToRegisteredConsumer() {
//		"Accepted errors are passed to a registered Consumer"
//		given: "a composable with a registered consumer of RuntimeExceptions"
		BalancedFluxProcessor<Integer> composable = Processors.emitter().build();
		LongAdder errors = new LongAdder();
		composable.asFlux().doOnError(e -> errors.increment()).subscribe();

//		when: "A RuntimeException is accepted"
		composable.onError(new RuntimeException());

//		then: "it is passed to the consumer"
		assertThat(errors.intValue()).isEqualTo(1);

//		when: "A new error consumer is subscribed"
		Flux.error(new RuntimeException()).doOnError(e -> errors.increment()).subscribe();

//		then: "it is called since publisher is in error state"
		assertThat(errors.intValue()).isEqualTo(2);
	}

	@Test
	public void whenAcceptedEventIsIterableSplitCanIterateOverValues() {
//		"When the accepted event is Iterable, split can iterate over values"
//		given: "a composable with a known number of values"
		BalancedFluxProcessor<Iterable<String>> d = Processors.emitter().build();
		Flux<String> composable = d.asFlux().flatMap(Flux::fromIterable);

//		when: "accept list of Strings"
		AtomicReference<String> tap = new AtomicReference<>();
		composable.subscribe(tap::set);
		d.onNext(Arrays.asList("a", "b", "c"));

//		then: "its value is the last of the initial values"
		assertThat(tap.get()).isEqualTo("c");
	}

	@Test
	public void fluxValuesCanBeMapped() {
//		"A Flux"s values can be mapped"
//		given: "a source composable with a mapping function"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Flux<Integer> mapped = source.asFlux().map(it -> it * 2);

//		when: "the source accepts a value"
		AtomicReference<Integer> value = new AtomicReference<>();
		mapped.subscribe(value::set);
		source.onNext(1);

//		then: "the value is mapped"
		assertThat(value.get()).isEqualTo(2);
	}

	@Test
	public void streamValuesCanBeExploded() {
//		Stream"s values can be exploded
//			given: "a source composable with a mapMany function"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Flux<Integer> mapped = source
				.asFlux()
				.log()
				.publishOn(Schedulers.parallel())
				.log()
				.flatMap(v -> Flux.just(v * 2))
				.doOnError(Throwable::printStackTrace);

//			when: "the source accepts a value"
		BalancedMonoProcessor<Integer> value = mapped.next()
		                                             .toProcessor();
		value.asMono().subscribe();
		source.sink().next(1);

//		then: "the value is mapped"
		int result = value.asMono().block(Duration.ofSeconds(5));
		assertThat(result).isEqualTo(2);
	}

	@Test
	public void multipleStreamValuesCanBeMerged() {
//		"Multiple Stream"s values can be merged"
//		given: "source composables to merge, buffer and tap"
		BalancedFluxProcessor<Integer> source1 = Processors.emitter().build();

		BalancedFluxProcessor<Integer> source2 = Processors.emitter().build();
		source2.asFlux()
		       .map(it -> it)
		       .map(it -> it); //FIXME this sequence is not used...

		BalancedFluxProcessor<Integer> source3 = Processors.emitter().build();

		AtomicReference<List<Integer>> tap = new AtomicReference<>();
		Flux.merge(source1, source2, source3).log().buffer(3)
		    .log().subscribe(tap::set);

//		when: "the sources accept a value"
		source1.onNext(1);
		source2.onNext(2);
		source3.onNext(3);

//		then: "the values are all collected from source1 flux"
		assertThat(tap.get()).containsExactly(1, 2, 3);
	}



	@Test
	public void aDifferentWayOfConsuming() {
//		"A different way of consuming"
//		given: "source composables to merge, buffer and tap"
		Flux<Integer> odds = Flux.just(1, 3, 5, 7, 9);
		Flux<Integer> even = Flux.just(2, 4, 6);

//		when: "the sources are zipped"
		Flux<Integer> mergedFlux = Flux.merge(odds, even);
		List<String> res = new ArrayList<>();

		mergedFlux.subscribe(
				it -> {
					res.add("" + it);
					System.out.println(it);
				},
				Throwable::printStackTrace,
				() -> {
					Collections.sort(res);
					res.add("done");
					System.out.println("completed!");
				});

//		then: "the values are all collected from source1 and source2 flux"
		assertThat(res).containsExactly("1", "2", "3", "4", "5", "6", "7", "9", "done");
	}

	@Test
	public void combineLatestStreamData() {
//		"Combine latest stream data"
//		given: "source composables to combine, buffer and tap"
		BalancedFluxProcessor<String> w1 = Processors.emitter().build();
		BalancedFluxProcessor<String> w2 = Processors.emitter().build();
		BalancedFluxProcessor<String> w3 = Processors.emitter().build();

//		when: "the sources are combined"
		Flux<String> mergedFlux =
				Flux.combineLatest(w1, w2, w3, t -> "" + t[0] + t[1] + t[2]);
		List<String> res = new ArrayList<>();

		mergedFlux.subscribe(
				it -> {
					res.add(it);
					System.out.println(it);
				}, Throwable::printStackTrace,
				() -> {
					Collections.sort(res);
					res.add("done");
					System.out.println("completed!");
				});

		w1.onNext("1a");
		w2.onNext("2a");
		w3.onNext("3a");
		w1.onComplete();
		// twice for w2
		w2.onNext("2b");
		w2.onComplete();
		// 4 times for w3
		w3.onNext("3b");
		w3.onNext("3c");
		w3.onNext("3d");
		w3.onComplete();


//		then: "the values are all collected from source1 and source2 flux"
		assertThat(res).containsExactly("1a2a3a", "1a2b3a", "1a2b3b", "1a2b3c", "1a2b3d", "done");
	}
	
	@Test
	public void simpleConcat() {
//		"A simple concat"
//		given: "source composables to concated, buffer and tap"
		Flux<Integer> firsts = Flux.just(1, 2, 3);
		Flux<Integer> lasts = Flux.just(4, 5);

//		when: "the sources are concat"
		Flux<Integer> mergedFlux = Flux.concat(firsts, lasts);
		List<String> res1 = new ArrayList<>();
		mergedFlux.subscribe(
				it -> {
					res1.add("" + it);
					System.out.println(it);
				}, Throwable::printStackTrace,
				() -> {
					res1.add("done");
				System.out.println("completed!");
				});

//		then: "the values are all collected from source1 and source2 flux"
		assertThat(res1).containsExactly("1", "2", "3", "4", "5", "done");

//		when:
		List<String> res2 = new ArrayList<>();
		lasts.startWith(firsts).subscribe(
				it -> {
					res2.add("" + it);
					System.out.println(it);
				}, Throwable::printStackTrace,
				() -> {
					res2.add("done");
					System.out.println("completed!");
				});

//		then: "the values are all collected from source1 and source2 flux"
		assertThat(res2).containsExactly("1", "2", "3", "4", "5", "done");

//		when:
		List<String> res3 = new ArrayList<>();
		lasts.startWith(1, 2, 3).subscribe(
				it -> {
					res3.add("" + it);
					System.out.println(it);
				}, Throwable::printStackTrace,
				() -> {
					res3.add("done");
					System.out.println("completed!");
				});

//		then: "the values are all collected from source1 and source2 flux"
		assertThat(res3).containsExactly("1", "2", "3", "4", "5", "done");
	}

	@Test
	public void mappedConcat() {
//		"A mapped concat"
//		given: "source composables to concatMap, buffer and tap"
		Flux<Integer> firsts = Flux.just(1, 2, 3);

//		when: "the sources are concatMap"
		Flux<Integer> mergedFlux = firsts.concatMap(it -> Flux.range(it, 2));
		List<String> res = new ArrayList<>();
		mergedFlux.subscribe(
				it -> {
					res.add("" + it);
					System.out.println(it);
				}, Throwable::printStackTrace,
				() -> {
					res.add("done");
					System.out.println("completed!");
				});

//		then: "the values are all collected from source1 and source2 flux"
		assertThat(res).containsExactly("1", "2", "2", "3", "3", "4", "done");
	}

	@Test
	public void streamCanBeCounted() {
//		"Stream can be counted"
//		given: "source composables to count and tap"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		BalancedMonoProcessor<Long> tap = source.asFlux()
		                                        .count()
		                                        .subscribeWith(Processors.<Long>first().build());

//		when: "the sources accept a value"
		source.onNext(1);
		source.onNext(2);
		source.onNext(3);
		source.onComplete();

//		then: "the count value matches the number of accept"
		assertThat(tap).isInstanceOf(Queue.class);
		assertThat(((Queue) tap).peek()).isEqualTo(3);
	}

	@Test
	public void fluxCanReturnValueAtCertainIndex() {
//		"A Flux can return a value at a certain index"
//		given: "a composable with values 1 to 5"
		Flux<Integer> s = Flux.just(1, 2, 3, 4, 5);
		LongAdder error = new LongAdder();
		Consumer<Throwable> errorConsumer = e -> error.increment();

//		when: "element at index 2 is requested"
		Integer tap = s.elementAt(2)
		               .block();

//		then: "3 is emitted"
		assertThat(tap).isEqualTo(3);

//		when: "element with negative index is requested"
//		then: "error is thrown"
		assertThatExceptionOfType(IndexOutOfBoundsException.class)
			.isThrownBy(() -> s.elementAt(-1));

//		when: "element with index > number of values is requested"
//		then: "error is thrown"
		assertThatExceptionOfType(IndexOutOfBoundsException.class)
				.isThrownBy(() -> s.elementAt(10).doOnError(errorConsumer).block());
		assertThat(error.intValue()).isEqualTo(1);
	}

	@Test
	public void fluxCanReturnValueAtCertainIndexOrDefaultValue() {
//		"A Flux can return a value at a certain index or a default value"
//		given: "a composable with values 1 to 5"
		Flux<Integer> s = Flux.just(1, 2, 3, 4, 5);

//		when: "element at index 2 is requested"
		Integer tap = s.elementAt(2, -1)
		               .block();

//		then: "3 is emitted"
		assertThat(tap).isEqualTo(3);

//		when: "element with index > number of values is requested"
		tap = s.elementAt(10, -1).block();

//		then: "-1 is emitted"
		assertThat(tap).isEqualTo(-1);
	}

	@Test
	public void fluxValuesCanBeFiltered() {
//		"A Flux"s values can be filtered"
//		given: "a source composable with a filter that rejects odd values"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Flux<Integer> filtered = source.asFlux().filter(it -> it % 2 == 0);

//		when: "the source accepts an even value"
		AtomicReference<Integer> value = new AtomicReference<>();
		filtered.subscribe(value::set);
		source.onNext(2);

//		then: "it passes through"
		assertThat(value.get()).isEqualTo(2);

//		when: "the source accepts an odd value"
		source.onNext(3);

//		then: "it is blocked by the filter"
		assertThat(value.get()).isEqualTo(2);

//		when: "simple filter"
		BalancedFluxProcessor<Boolean> anotherSource = Processors.emitter().build();
		AtomicBoolean tap = new AtomicBoolean();
		anotherSource.asFlux().filter(it -> it).subscribe(tap::set);
		anotherSource.onNext(true);

//		then: "it is accepted by the filter"
		assertThat(tap.get()).isTrue();

//		when: "simple filter nominal case"
		anotherSource = Processors.emitter().build();
		anotherSource.asFlux().filter(it -> it).subscribe(tap::set);
		anotherSource.onNext(false);

//		then: "it is not accepted by the filter (previous value held)"
		assertThat(tap.get()).isTrue();
	}

	@Test
	public void whenMappingFunctionThrowsMappedComposableAcceptsError() {
//		"When a mapping function throws an exception, the mapped composable accepts the error"
//		given: "a source composable with a mapping function that throws an error"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Flux<String> mapped = source.asFlux().map(it -> {
					if (it == 1) {
						throw new RuntimeException();
					}
					else {
						return "na";
					}
		});

		LongAdder errors = new LongAdder();
		mapped.doOnError(e -> errors.increment())
		      .subscribe();

//		when: "the source accepts a value"
		source.onNext(1);

//		then: "the error is passed on"
		assertThat(errors.intValue()).isEqualTo(1);
	}

	@Test
	public void whenProcessorIsStreamed() {
//		"When a processor is streamed"
//		given: "a source composable and a async downstream"
		BalancedFluxProcessor<Integer> source = Processors.replay().build();
		Scheduler scheduler = Schedulers.newParallel("test", 2);

		try {
			Mono<List<Integer>> res = source.asFlux()
			                                .subscribeOn(scheduler)
			                                .delaySubscription(Duration.ofMillis(1L))
			                                .log("streamed")
			                                .map(it -> it * 2)
			                                .buffer()
			                                .publishNext();

			res.subscribe();

//		when: "the source accepts a value"
			source.onNext(1);
			source.onNext(2);
			source.onNext(3);
			source.onNext(4);
			source.onComplete();

//		then: "the res is passed on"
			assertThat(res.block()).containsExactly(2, 4, 6, 8);
		}
		finally {
			scheduler.dispose();
		}
	}

	@Test
	public void whenFilterFunctionThrowsFilteredComposableAcceptsError() {
//		"When a filter function throws an exception, the filtered composable accepts the error"
//		given: "a source composable with a filter function that throws an error"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Flux<Integer> filtered = source.asFlux().filter(it -> {
			if (it == 1) {
				throw new RuntimeException();
			}
			else {
				return true;
			}
		});
		LongAdder errors = new LongAdder();
		filtered.doOnError(e -> errors.increment()).subscribe();

//		when: "the source accepts a value"
		source.onNext(1);

//		then: "the error is passed on"
		assertThat(errors.intValue()).isEqualTo(1);
	}

	@Test
	public void knownSetOfValuesCanBeReduced() {
//		"A known push of values can be reduced"
//		given: "a composable with a known push of values"
		Flux<Integer> source = Flux.fromIterable(Arrays.asList(1, 2, 3, 4, 5));

//		when: "a reduce function is registered"
		Mono<Integer> reduced = source.reduce(new Reduction());
		Integer value = reduced.block();

//		then: "the resulting composable holds the reduced value"
		assertThat(value).isEqualTo(120);

//		when: "use an initial value"
		value = source.reduce(2, new Reduction()).block();

//		then: "the updated reduction is available"
		assertThat(value).isEqualTo(240);
	}

	@Test
	public void whenReducingKnownSetOfValuesOnlyFinalValueIsPassedToConsumers() {
//		"When reducing a known push of values, only the final value is passed to consumers"
//		given: "a composable with a known push of values and a reduce function"
		Mono<Integer> reduced = Flux.just(1, 2, 3, 4, 5).reduce(new Reduction());

//		when: "a consumer is registered"
		Integer values = reduced.block();

//		then: "the consumer only receives the final value"
		assertThat(values).isEqualTo(120);
	}

	@Test
	public void whenReducingKnownNumberOfValuesOnlyFinalValueIsPassedToConsumers() {
//		"When reducing a known number of values, only the final value is passed to consumers"
//		given: "a composable with a known number of values and a reduce function"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Mono<Integer> reduced = source.asFlux().reduce(new Reduction());
		List<Integer> values = new ArrayList<>();
		reduced.doOnSuccess(values::add).subscribe();

//		when: "the expected number of values is accepted"
		source.onNext(1);
		source.onNext(2);
		source.onNext(3);
		source.onNext(4);
		source.onNext(5);
		source.onComplete();

//		then: "the consumer only receives the final value"
		assertThat(values).containsExactly(120);
	}

	@Test
	public void knownNumberOfValuesCanBeReduced() {
//		"A known number of values can be reduced"
//		given: "a composable that will accept 5 values and a reduce function"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Mono<Integer> reduced = source.asFlux().reduce(new Reduction());
		BalancedMonoProcessor<Integer> value = reduced.subscribeWith(Processors.<Integer>first().build());

//		when: "the expected number of values is accepted"
		source.onNext(1);
		source.onNext(2);
		source.onNext(3);
		source.onNext(4);
		source.onNext(5);
		source.onComplete();

//		then: "the reduced composable holds the reduced value"
		assertThat(value).isInstanceOf(Queue.class);
		assertThat(((Queue) value).peek()).isEqualTo(120);
	}

	@Test
	public void whenKnownNumberOfValuesIsReducedOnlyFinalValueMadeAvailable() {
//		"When a known number of values is being reduced, only the final value is made available"
//		given: "a composable that will accept 2 values and a reduce function"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		BalancedMonoProcessor<Integer> value = source.asFlux()
		                                             .reduce(new Reduction())
		                                             .subscribeWith(Processors.<Integer>first().build());
		Queue valueAsQueue = (Queue) value;


//		when: "the first value is accepted"
		source.onNext(1);

//		then: "the reduced value is unknown"
		assertThat(valueAsQueue.peek()).isNull();

//		when: "the second value is accepted"
		source.onNext(2);
		source.onComplete();

//		then: "the reduced value is known"
		assertThat(valueAsQueue.peek()).isEqualTo(2);
	}



	@Test
	public void whenUnknownNumberOfValueScannedEachReductionPassedToConsumer() {
//		"When an unknown number of values is being scanned, each reduction is passed to a consumer"
//		given: "a composable with a reduce function"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Flux<Integer> reduced = source.asFlux().scan(new Reduction());
		AtomicReference<Integer> value = new AtomicReference<>();
		reduced.subscribe(value::set);

//		when: "the first value is accepted"
		source.onNext(1);

//		then: "the reduction is available"
		assertThat(value.get()).isEqualTo(1);

//		when: "the second value is accepted"
		source.onNext(2);

//		then: "the updated reduction is available"
		assertThat(value.get()).isEqualTo(2);

//		when: "use an initial value"
		source.asFlux().scan(4, new Reduction()).subscribe(value::set);
		source.onNext(1);

//		then: "the updated reduction is available"
		assertThat(value.get()).isEqualTo(4);
	}

	@Test
	public void reduceWillAccumulateListOfAcceptedValues() {
//		"Reduce will accumulate a list of accepted values"
//		given: "a composable"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Mono<List<Integer>> reduced = source.asFlux().collectList();
		BalancedMonoProcessor<List<Integer>> value = reduced.toProcessor();
		value.asMono().subscribe();

//		when: "the first value is accepted"
		source.onNext(1);
		source.onComplete();

//		then: "the list contains the first element"
		assertThat(value.asMono().block()).containsExactly(1);
	}

	@Test
	public void whenUnknownNumberOfValuesReducedEachReductionPassedToConsumerOnWindow() {
//		"When an unknown number of values is being reduced, each reduction is passed to a consumer on window"
//		given: "a composable with a reduce function"
		BalancedFluxProcessor<Integer> source = Processors.emitter().build();
		Flux<Integer> reduced = source.asFlux()
		                              .window(2)
		                              .log()
		                              .flatMap(it -> it.log("lol")
		                                               .reduce(new Reduction()));
		BalancedMonoProcessor<Integer> value = reduced.subscribeWith(Processors.<Integer>first().build());
		Queue valueAsQueue = (Queue) value;

//		when: "the first value is accepted"
		source.onNext(1);

//		then: "the reduction is not available"
		assertThat(valueAsQueue.peek()).isNull();

//		when: "the second value is accepted and flushed"
		source.onNext(2);

//		then: "the updated reduction is available"
		assertThat(valueAsQueue.peek()).isEqualTo(2);
	}



	@Test
	public void countRange(){
		StepVerifier.create(Flux.range(1, 10).count())
	                .expectNext(10L)
	                .verifyComplete();
	}

	Flux<List<Integer>> scenario_delayItems() {
		return Flux.range(1, 4)
		           .buffer(2)
		           .delayElements(Duration.ofMillis(1000));
	}

	@Test
	public void delayItems() {
		StepVerifier.withVirtualTime(this::scenario_delayItems)
		            .thenAwait(Duration.ofMillis(2000))
		            .assertNext(s -> assertThat(s).containsExactly(1, 2))
		            .thenAwait(Duration.ofMillis(2000))
		            .assertNext(s -> assertThat(s).containsExactly(3, 4))
		            .verifyComplete();
	}


	Mono<Long> scenario_fluxItemCanBeShiftedByTime() {
		return Flux.range(0, 10000)
		           .delayElements(Duration.ofMillis(150))
		           .elapsed()
		           .take(10)
		           .reduce(0L,
				           (acc, next) -> acc > 0l ? ((next.getT1() + acc) / 2) :
						           next.getT1());

	}

	@Test
	public void fluxItemCanBeShiftedByTime() {
		StepVerifier.withVirtualTime(this::scenario_fluxItemCanBeShiftedByTime)
		            .thenAwait(Duration.ofMillis(15_000))
		            .expectNext(150L)
		            .verifyComplete();
	}

	Mono<Long> scenario_fluxItemCanBeShiftedByTime2() {
		return Flux.range(0, 10000)
		           .delayElements(Duration.ofMillis(150))
		           .elapsed()
		           .take(10)
		           .reduce(0L,
				           (acc, next) -> acc > 0l ? ((next.getT1() + acc) / 2) :
						           next.getT1());

	}

	@Test
	public void fluxItemCanBeShiftedByTime2() {
		StepVerifier.withVirtualTime(this::scenario_fluxItemCanBeShiftedByTime2)
		            .thenAwait(Duration.ofMillis(15_000))
		            .expectNext(150L)
		            .verifyComplete();
	}

	@Test(timeout = 10000L)
	public void collectFromMultipleThread1() throws Exception {
		BalancedFluxProcessor<Integer> head = Processors.emitter().build();
		AtomicInteger sum = new AtomicInteger();

		int length = 1000;
		int batchSize = 333;
		int latchCount = length / batchSize;
		CountDownLatch latch = new CountDownLatch(latchCount);

		head
				.asFlux()
				.publishOn(Schedulers.parallel())
				.parallel(3)
				.runOn(Schedulers.parallel())
				.collect(ArrayList::new, List::add)
				.subscribe(ints -> {
					sum.addAndGet(ints.size());
					latch.countDown();
				});

		Flux.range(1, 1000).subscribe(head);
		latch.await();
		Assert.assertTrue(sum.get() == length);
	}

	static class Reduction implements BiFunction<Integer, Integer, Integer> {
		@Override
		public Integer apply(Integer left, Integer right) {
			Integer result = right == null ? 1 : left * right;
			System.out.println(right + " " + left + " reduced to " + result);
			return result;
		}
	}

}
