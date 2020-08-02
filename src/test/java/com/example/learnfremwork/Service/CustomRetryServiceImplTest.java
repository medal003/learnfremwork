package com.example.learnfremwork.Service;

import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.RetryState;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.UniformRandomBackOffPolicy;
import org.springframework.retry.policy.CircuitBreakerRetryPolicy;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.DefaultRetryState;
import org.springframework.retry.support.RetryTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * @author meideng.zh <br/>
 * date: 2020/8/2/0002 11:40:41 <br/>
 * comment: 
 */
class CustomRetryServiceImplTest {

    @Test
    void simpleRetry() {
        RetryTemplate retryTemplate = new RetryTemplate();
        AtomicInteger counter = new AtomicInteger();
        RetryCallback<Integer, IllegalStateException> retryCallback = retryContext -> {
            if (counter.incrementAndGet() < 3) {//内部默认重试策略是最多尝试3次，即最多重试两次。
                throw new IllegalStateException();
            }
            return counter.incrementAndGet();
        };
        Integer result = retryTemplate.execute(retryCallback);

        Assert.assertEquals(4, result.intValue());
    }

    @Test
    void recoveryCallback() {
        RetryTemplate retryTemplate = new RetryTemplate();
        AtomicInteger counter = new AtomicInteger();
        RetryCallback<Integer, IllegalStateException> retryCallback = retryContext -> {
            //内部默认重试策略是最多尝试3次，即最多重试两次。还不成功就会抛出异常。
            if (counter.incrementAndGet() < 10) {
                throw new IllegalStateException();
            }
            return counter.incrementAndGet();
        };

        RecoveryCallback<Integer> recoveryCallback = retryContext -> {
            //返回的应该是30。RetryContext.getRetryCount()记录的是尝试的次数，一共尝试了3次。
            return retryContext.getRetryCount() * 10;
        };
        //尝试策略已经不满足了，将不再尝试的时候会抛出异常。此时如果指定了RecoveryCallback将执行RecoveryCallback，
        //然后获得返回值。
        Integer result = retryTemplate.execute(retryCallback, recoveryCallback);

        Assert.assertEquals(30, result.intValue());
    }

    /***
     * comment:  <br/>

     SimpleRetryPolicy在判断一个异常是否可重试时，默认会取最后一个抛出的异常。我们通常可能在不同的业务层面包装不同的异常，比如有些场景我们可能需要把捕获到的异常都包装为BusinessException，比如说把一个IllegalStateException包装为BusinessException。我们程序中定义了所有的IllegalStateException是可以进行重试的，如果SimpleRetryPolicy直接取的最后一个抛出的异常会取到BusinessException。这可能不是我们想要的，此时可以通过构造参数traverseCauses指定可以遍历异常栈上的每一个异常进行判断。比如下面代码，在traverseCauses=false时，只会在抛出IllegalStateException时尝试3次，第四次抛出的Exception不是RuntimeException，所以不会进行重试。指定了traverseCauses=true时第四次尝试时抛出的Exception，再往上找时会找到IllegalArgumentException，此时又可以继续尝试，所以最终执行后counter的值会是6。

     SimpleRetryPolicy除了前面介绍的3个构造方法外，还有如下这样一个构造方法，它的第四个参数表示当抛出的异常是在retryableExceptions中没有定义是否需要尝试时其默认的值，该值为true则表示默认可尝试。
     */
    @SneakyThrows
    @Test
    void simpleRetryPolicy() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = Maps.newHashMap();
        retryableExceptions.put(RuntimeException.class, true);
        RetryPolicy retryPolicy = new SimpleRetryPolicy(10, retryableExceptions, true);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        AtomicInteger counter = new AtomicInteger();
        final Integer integer = retryTemplate.execute(retryContext -> {
            if (counter.incrementAndGet() < 3) {
                throw new IllegalStateException();
            } else if (counter.incrementAndGet() < 6) {
                try {
                    throw new IllegalArgumentException();
                } catch (Exception e) {
                    throw new Exception(e);
                }
            }
            return counter.get();
        });
        System.out.println(integer);
    }

    /*
     TimeoutRetryPolicy用于在指定时间范围内进行重试，直到超时为止，默认的超时时间是1000毫秒。
     */
    @SneakyThrows
    @Test
    void timeoutRetryPolicy() {
        TimeoutRetryPolicy retryPolicy = new TimeoutRetryPolicy();
        retryPolicy.setTimeout(2000);//不指定时默认是1000
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        AtomicInteger counter = new AtomicInteger();
        Integer result = retryTemplate.execute(retryContext -> {
            if (counter.incrementAndGet() < 10) {
                TimeUnit.MILLISECONDS.sleep(300); // 设置休眠时间
                throw new IllegalStateException();
            }
            return counter.get();
        });
        Assert.assertEquals(10, result.intValue());
    }

    /***
     之前介绍的SimpleRetryPolicy可以基于异常来判断是否需要进行重试。如果你需要基于不同的异常应用不同的重试策略怎么办呢？ExceptionClassifierRetryPolicy可以帮你实现这样的需求。下面的代码中我们就指定了当捕获的是IllegalStateException时将最多尝试5次，当捕获的是IllegalArgumentException时将最多尝试4次。其执行结果最终是抛出IllegalArgumentException的，但是在最终抛出IllegalArgumentException时counter的值是多少呢？换句话说它一共尝试了几次呢？答案是8次。按照笔者的写法，进行第5次尝试时不会抛出IllegalStateException，而是抛出IllegalArgumentException，它对于IllegalArgumentException的重试策略而言是第一次尝试，之后会再尝试3次，5+3=8，所以counter的最终的值是8。

     */
    @Test
    void exceptionClassifierRetryPolicy() {
        ExceptionClassifierRetryPolicy retryPolicy = new ExceptionClassifierRetryPolicy();

        Map<Class<? extends Throwable>, RetryPolicy> policyMap = Maps.newHashMap();
        policyMap.put(IllegalStateException.class, new SimpleRetryPolicy(5));
        policyMap.put(IllegalArgumentException.class, new SimpleRetryPolicy(4));
        retryPolicy.setPolicyMap(policyMap);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        AtomicInteger counter = new AtomicInteger();
        final Integer integer = retryTemplate.execute(retryContext -> {
            if (counter.incrementAndGet() < 5) {
                throw new IllegalStateException();
            } else if (counter.get() < 10) {
                throw new IllegalArgumentException();
            }
            return counter.get();
        });
        System.out.println(integer);
    }

    /**
     CircuitBreakerRetryPolicy是包含了断路器功能的RetryPolicy，它内部默认包含了一个SimpleRetryPolicy，最多尝试3次。在固定的时间窗口内（默认是20秒）如果底层包含的RetryPolicy的尝试次数都已经耗尽了，则其会打开断路器，默认打开时间是5秒，在这段时间内如果还有其它请求过来就不会再进行调用了。CircuitBreakerRetryPolicy需要跟RetryState一起使用，下面的代码中RetryTemplate使用的是CircuitBreakerRetryPolicy，一共调用了5次execute()，每次调用RetryCallback都会抛出IllegalStateException，并且会打印counter的当前值，前三次RetryCallback都是可以运行的，之后断路器打开了，第四五次执行execute()时就不会再执行RetryCallback了，所以你只能看到只进行了3次打印。
     */
    @Test
    void circuitBreakerRetryPolicy() {
        CircuitBreakerRetryPolicy retryPolicy = new CircuitBreakerRetryPolicy();
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        AtomicInteger counter = new AtomicInteger();
        RetryState retryState = new DefaultRetryState("key");

        /**
         断路器默认打开的时间是5秒，5秒之后断路器又会关闭，RetryCallback又可以正常调用了。判断断路器是否需要打开的时间窗口默认是20秒，即在20秒内所有的尝试次数都用完了，就会打开断路器。如果在20秒内只尝试了两次（默认3次），则在新的时间窗口内尝试次数又将从0开始计算。可以通过如下方式进行这两个时间的设置。
         SimpleRetryPolicy delegate = new SimpleRetryPolicy(5);
         //底层允许最多尝试5次
         CircuitBreakerRetryPolicy retryPolicy = new CircuitBreakerRetryPolicy(delegate);
         retryPolicy.setOpenTimeout(2000);//断路器打开的时间
         retryPolicy.setResetTimeout(15000);//时间窗口
         */
        for (int i=0; i<5; i++) {
            try {
                retryTemplate.execute(retryContext -> {
                    System.out.println(LocalDateTime.now() + "----" + counter.get());
                    TimeUnit.MILLISECONDS.sleep(100);
                    if (counter.incrementAndGet() > 0) {
                        throw new IllegalStateException();
                    }
                    return 1;
                }, null, retryState);
            } catch (Exception e) {

            }
        }
    }

    /**
     CompositeRetryPolicy可以用来组合多个RetryPolicy，可以设置必须所有的RetryPolicy都是可以重试的时候才能进行重试，也可以设置只要有一个RetryPolicy可以重试就可以进行重试。默认是必须所有的RetryPolicy都可以重试才能进行重试。下面代码中应用的就是CompositeRetryPolicy，它组合了两个RetryPolicy，最多尝试5次的SimpleRetryPolicy和超时时间是2秒钟的TimeoutRetryPolicy，所以它们的组合就是必须尝试次数不超过5次且尝试时间不超过2秒钟才能进行重试。execute()中执行的RetryCallback的逻辑是counter的值小于10时就抛出IllegalStateException，否则就返回counter的值。第一次尝试的时候会失败，第二次也是，直到第5次尝试也还是失败的，此时SimpleRetryPolicy已经不能再尝试了，而TimeoutRetryPolicy此时还是可以尝试的，但是由于前者已经不能再尝试了，所以整体就不能再尝试了。所以下面的执行会以抛出IllegalStateException告终。
     */
    @Test
    void compositeRetryPolicy() {
        CompositeRetryPolicy compositeRetryPolicy = new CompositeRetryPolicy();
        RetryPolicy policy1 = new SimpleRetryPolicy(5);
        TimeoutRetryPolicy policy2 = new TimeoutRetryPolicy();
        policy2.setTimeout(2000);
        RetryPolicy[] policies = new RetryPolicy[]{policy1, policy2};
        compositeRetryPolicy.setPolicies(policies);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(compositeRetryPolicy);
        AtomicInteger counter = new AtomicInteger();
        retryTemplate.execute(retryContext -> {
            if (counter.incrementAndGet() < 10) {
                throw new IllegalStateException();
            }
            return counter.get();
        });
    }

    /**
     CompositeRetryPolicy也支持组合的RetryPolicy中只要有一个RetryPolicy满足条件就可以进行重试，这是通过参数optimistic控制的，默认是false，改为true即可。比如下面设置了setOptimistic(true)，那么中尝试5次后SimpleRetryPolicy已经不满足了，但是TimeoutRetryPolicy还满足条件，所以最终会一直尝试，直到counter的值为10。
     */
    @Test
    void compositeRetryPolicy1() {
        CompositeRetryPolicy compositeRetryPolicy = new CompositeRetryPolicy();
        RetryPolicy policy1 = new SimpleRetryPolicy(5);
        TimeoutRetryPolicy policy2 = new TimeoutRetryPolicy();
        policy2.setTimeout(2000);
        RetryPolicy[] policies = new RetryPolicy[]{policy1, policy2};
        compositeRetryPolicy.setPolicies(policies);
        compositeRetryPolicy.setOptimistic(true);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(compositeRetryPolicy);
        AtomicInteger counter = new AtomicInteger();
        Integer result = retryTemplate.execute(retryContext -> {
            if (counter.incrementAndGet() < 10) {
                throw new IllegalStateException();
            }
            return counter.get();
        });
        Assert.assertEquals(10, result.intValue());
    }

    /**
     BackOffPolicy用来定义在两次尝试之间需要间隔的时间，RetryTemplate内部默认使用的是NoBackOffPolicy，其在两次尝试之间不会进行任何的停顿。对于一般可重试的操作往往是基于网络进行的远程请求，它可能由于网络波动暂时不可用，如果立马进行重试它可能还是不可用，但是停顿一下，过一会再试可能它又恢复正常了，所以在RetryTemplate中使用BackOffPolicy往往是很有必要的。
     */

    /*FixedBackOffPolicy将在两次重试之间进行一次固定的时间间隔，默认是1秒钟，也可以通过setBackOffPeriod()进行设置。下面代码中指定了两次重试的时间间隔是1秒钟，第一次尝试会失败，等一秒后会进行第二次尝试，第二次尝试会成功。*/
    @Test
    void backOffPolicy() {
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(1000);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setBackOffPolicy(backOffPolicy);

        long t1 = System.currentTimeMillis();
        long t2 = retryTemplate.execute(retryContext -> {
            if (System.currentTimeMillis() - t1 < 1000) {
                throw new IllegalStateException();
            }
            return System.currentTimeMillis();
        });
        Assert.assertTrue(t2 - t1 > 1000);
        Assert.assertTrue(t2 - t1 < 1100);
    }

    /**
     ExponentialBackOffPolicy可以使每一次尝试的间隔时间都不一样，它有3个重要的参数，初始间隔时间、后一次间隔时间相对于前一次间隔时间的倍数和最大的间隔时间，它们的默认值分别是100毫秒、2.0和30秒。下面的代码使用了ExponentialBackOffPolicy，指定了初始间隔时间是1000毫秒，每次间隔时间以2倍的速率递增，最大的间隔时间是5000毫秒，它最多可以尝试10次。所以当第1次尝试失败后会间隔1秒后进行第2次尝试，之后再间隔2秒进行第3次尝试，之后再间隔4秒进行第4次尝试，之后都是间隔5秒再进行下一次尝试，因为再翻倍已经超过了设定的最大的间隔时间。
     */
    @Test
    void backOffPolicy1() {
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000);
        backOffPolicy.setMaxInterval(5000);
        backOffPolicy.setMultiplier(2.0);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setBackOffPolicy(backOffPolicy);
        int maxAttempts = 10;
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts));

        long t1 = System.currentTimeMillis();
        long t2 = retryTemplate.execute(retryContext -> {
            if (retryContext.getRetryCount() < maxAttempts-1) {//最后一次尝试会成功
                throw new IllegalStateException();
            }
            return System.currentTimeMillis();
        });
        long time = 0 + 1000 + 1000 * 2 + 1000 * 2 * 2 + 5000 * (maxAttempts - 4);
        Assert.assertTrue((t2-t1) - time < 100);
    }

    /**
     ExponentialRandomBackOffPolicy的用法跟ExponentialBackOffPolicy的用法是一样的，它继承自ExponentialBackOffPolicy，在确定间隔时间时会先按照ExponentialBackOffPolicy的方式确定一个时间间隔，然后再随机的增加一个0-1的。比如取得的随机数是0.1即表示增加10%，每次需要确定重试间隔时间时都会产生一个新的随机数。如果指定的初始间隔时间是100毫秒，增量倍数是2,最大间隔时间是2000毫秒，则按照ExponentialBackOffPolicy的重试间隔是100、200、400、800,而ExponentialRandomBackOffPolicy产生的间隔时间可能是111、256、421、980。下面代码使用了ExponentialRandomBackOffPolicy，它打印出了每次重试时间的间隔。如果你有兴趣，你运行它会看到它会在ExponentialBackOffPolicy的基础上每次都随机的增长0-1倍。
     */
    @Test
    void backOffPolicy2() {
        ExponentialRandomBackOffPolicy backOffPolicy = new ExponentialRandomBackOffPolicy();
        backOffPolicy.setInitialInterval(1000);
        backOffPolicy.setMaxInterval(5000);
        backOffPolicy.setMultiplier(2.0);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setBackOffPolicy(backOffPolicy);
        int maxAttempts = 10;
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts));

        String lastAttemptTime = "lastAttemptTime";
        retryTemplate.execute(retryContext -> {
            if (retryContext.hasAttribute(lastAttemptTime)) {
                System.out.println(System.currentTimeMillis() - (Long) retryContext.getAttribute(lastAttemptTime));
            }
            retryContext.setAttribute(lastAttemptTime, System.currentTimeMillis());
            if (retryContext.getRetryCount() < maxAttempts-1) {//最后一次尝试会成功
                throw new IllegalStateException();
            }
            return System.currentTimeMillis();
        });
    }

    /**
     UniformRandomBackOffPolicy用来每次都随机的产生一个间隔时间，默认的间隔时间是在500-1500毫秒之间。可以通过setMinBackOffPeriod()设置最小间隔时间，通过setMaxBackOffPeriod()设置最大间隔时间。
     */
    @Test
    void backOffPolicy3() {
        UniformRandomBackOffPolicy backOffPolicy = new UniformRandomBackOffPolicy();
        backOffPolicy.setMinBackOffPeriod(1000);
        backOffPolicy.setMaxBackOffPeriod(3000);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setBackOffPolicy(backOffPolicy);
        int maxAttempts = 10;
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts));

        String lastAttemptTime = "lastAttemptTime";
        retryTemplate.execute(retryContext -> {
            if (retryContext.hasAttribute(lastAttemptTime)) {
                System.out.println(System.currentTimeMillis() - (Long) retryContext.getAttribute(lastAttemptTime));
            }
            retryContext.setAttribute(lastAttemptTime, System.currentTimeMillis());
            if (retryContext.getRetryCount() < maxAttempts-1) {//最后一次尝试会成功
                throw new IllegalStateException();
            }
            return System.currentTimeMillis();
        });
    }

    /**
     RetryTemplate中可以注册一些RetryListener，它可以用来对整个Retry过程进行监听。RetryListener的定义如下，它可以在整个Retry前、整个Retry后和每次Retry失败时进行一些操作。
     */
    @Test
    void retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        AtomicInteger counter = new AtomicInteger();
        RetryCallback<Integer, IllegalStateException> retryCallback = retryContext -> {
            //内部默认重试策略是最多尝试3次，即最多重试两次。还不成功就会抛出异常。
            if (counter.incrementAndGet() < 3) {
                throw new IllegalStateException();
            }
            return counter.incrementAndGet();
        };

        // 如果只想关注RetryListener的某些方法，则可以选择继承RetryListenerSupport，它默认实现了RetryListener的所有方法。
        RetryListener retryListener = new RetryListener() {
            @Override
            public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
                System.out.println("---open----在第一次重试时调用");
                return true;
            }

            @Override
            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                System.out.println("close----在最后一次重试后调用（无论成功与失败）。" + context.getRetryCount());
            }

            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                System.out.println("error----在每次调用异常时调用。" + context.getRetryCount());
            }
        };

        retryTemplate.registerListener(retryListener);
        retryTemplate.execute(retryCallback);
    }
}