/*
 * Copyright (c) 2015 The Jupiter Project
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jupiter.rpc.consumer;

import org.jupiter.common.concurrent.atomic.AtomicUpdater;
import org.jupiter.common.util.Lists;
import org.jupiter.common.util.Reflects;
import org.jupiter.common.util.Strings;
import org.jupiter.common.util.internal.Recyclers;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.rpc.*;
import org.jupiter.rpc.annotation.ServiceProvider;
import org.jupiter.rpc.aop.ConsumerHook;
import org.jupiter.rpc.consumer.dispatcher.DefaultBroadcastDispatcher;
import org.jupiter.rpc.consumer.dispatcher.DefaultRoundDispatcher;
import org.jupiter.rpc.consumer.dispatcher.Dispatcher;
import org.jupiter.rpc.consumer.invoker.AsyncInvoker;
import org.jupiter.rpc.consumer.invoker.SyncInvoker;
import org.jupiter.rpc.model.metadata.ServiceMetadata;

import java.lang.reflect.InvocationHandler;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.jupiter.common.util.Preconditions.checkArgument;
import static org.jupiter.common.util.Preconditions.checkNotNull;
import static org.jupiter.rpc.AsyncMode.ASYNC_CALLBACK;
import static org.jupiter.rpc.AsyncMode.SYNC;
import static org.jupiter.rpc.DispatchMode.BROADCAST;
import static org.jupiter.rpc.DispatchMode.ROUND;

/**
 * ProxyFactory是池化的, 每次 {@link #create()} 即可, 使用完后会自动回收, 不要创建一个反复使用.
 *
 * jupiter
 * org.jupiter.rpc.consumer
 *
 * @author jiachun.fjc
 */
public class ProxyFactory {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ProxyFactory.class);

    private static final AtomicIntegerFieldUpdater<ProxyFactory> updater;
    static {
        updater = AtomicUpdater.newAtomicIntegerFieldUpdater(ProxyFactory.class, "recycled");
    }
    // 个别使用者可能喜欢频繁创建ProxyFactory实例, 相比较AtomicBoolean, 使用AtomicIntegerFieldUpdater来更新volatile int的方式
    // 在64位虚拟机环境中会节省12(开启压缩指针的情况下)个字节的对象头大小.
    // http://hg.openjdk.java.net/jdk7u/jdk7u/hotspot/file/6e9aa487055f/src/share/vm/oops/klass.hpp
    //  [header         ] 8  byte
    //  [klass pointer  ] 8  byte (4 byte for compressed-oops)
    private volatile int recycled = 0; // 0: 可用; 1: 不可用(已被回收)

    private JClient client;
    private List<UnresolvedAddress> addresses;
    private Class<?> serviceInterface;
    private AsyncMode asyncMode = SYNC;
    private DispatchMode dispatchMode = ROUND;
    private int timeoutMills;
    private List<ConsumerHook> hooks;
    private JListener listener;

    public static ProxyFactory create() {
        ProxyFactory fac = recyclers.get();

        // 初始化数据
        fac.addresses = Lists.newArrayList();
        fac.hooks = Lists.newArrayListWithCapacity(4);
        fac.hooks.add(logConsumerHook);

        // 对当前线程可见就可以了
        // 用Unsafe.putOrderedXXX()消除写volatile的write barrier, JIT以后去掉了StoreLoad, 只剩StoreStore(x86下是空操作)
        // 在x86架构cpu上, StoreLoad是一条 [lock addl $0x0,(%rsp)] 指令, 去掉这条指令会有一点性能的提升 ^_^
        updater.lazySet(fac, 0);

        return fac;
    }

    /**
     * Sets the connector.
     */
    public ProxyFactory connector(JClient client) {
        checkValid(this);

        this.client = client;
        return this;
    }

    /**
     * Adds provider's addresses.
     */
    public ProxyFactory addProviderAddress(UnresolvedAddress... addresses) {
        checkValid(this);

        Collections.addAll(this.addresses, addresses);
        return this;
    }

    /**
     * Adds provider's addresses.
     */
    public ProxyFactory addProviderAddress(List<UnresolvedAddress> addresses) {
        checkValid(this);

        this.addresses.addAll(addresses);
        return this;
    }

    /**
     * Sets the service interface type.
     */
    public <I> ProxyFactory interfaceClass(Class<I> serviceInterface) {
        checkValid(this);

        this.serviceInterface = serviceInterface;
        return this;
    }

    /**
     * Synchronous blocking or asynchronous callback, the default is synchronous.
     */
    public ProxyFactory asyncMode(AsyncMode asyncMode) {
        checkValid(this);

        this.asyncMode = checkNotNull(asyncMode);
        return this;
    }

    /**
     * Sets the mode of dispatch, the default is {@link DispatchMode#ROUND}
     */
    public ProxyFactory dispatchMode(DispatchMode dispatchMode) {
        checkValid(this);

        this.dispatchMode = checkNotNull(dispatchMode);
        return this;
    }

    /**
     * Timeout milliseconds.
     */
    public ProxyFactory timeoutMills(int timeoutMills) {
        checkValid(this);

        this.timeoutMills = timeoutMills;
        return this;
    }

    /**
     * Asynchronous callback listener.
     */
    public ProxyFactory listener(JListener listener) {
        checkValid(this);

        if (asyncMode != ASYNC_CALLBACK) {
            throw new UnsupportedOperationException("asyncMode should first be set to ASYNC_CALLBACK");
        }
        this.listener = listener;
        return this;
    }

    /**
     * Adds hooks.
     */
    public ProxyFactory addHook(ConsumerHook... hooks) {
        checkValid(this);

        Collections.addAll(this.hooks, hooks);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <I> I newProxyInstance() {
        if (!updater.compareAndSet(this, 0, 1)) {
            throw new IllegalStateException(Reflects.simpleClassName(this) + " is used by others, you should create another one!");
        }

        try {
            // check arguments
            checkNotNull(client, "connector");
            checkNotNull(serviceInterface, "serviceInterface");
            checkArgument(!(asyncMode == SYNC && dispatchMode == BROADCAST), "illegal mode, [SYNC & BROADCAST] unsupported");
            ServiceProvider annotation = serviceInterface.getAnnotation(ServiceProvider.class);
            checkNotNull(annotation, serviceInterface + " is not a ServiceProvider interface");
            String providerName = annotation.value();
            providerName = Strings.isNotBlank(providerName) ? providerName : serviceInterface.getSimpleName();

            // metadata
            ServiceMetadata metadata = new ServiceMetadata(annotation.group(), annotation.version(), providerName);

            for (UnresolvedAddress address : addresses) {
                client.addChannelGroup(metadata, client.group(address));
            }

            // dispatcher
            Dispatcher dispatcher = null;
            switch (dispatchMode) {
                case ROUND:
                    dispatcher = new DefaultRoundDispatcher(client, metadata);
                    break;
                case BROADCAST:
                    dispatcher = new DefaultBroadcastDispatcher(client, metadata);
                    break;
            }
            if (timeoutMills > 0) {
                dispatcher.setTimeoutMills(timeoutMills);
            }
            dispatcher.setHooks(hooks);

            // invocation handler
            InvocationHandler handler = null;
            switch (asyncMode) {
                case SYNC:
                    handler = new SyncInvoker(dispatcher);
                    break;
                case ASYNC_CALLBACK:
                    dispatcher.setListener(checkNotNull(listener, "listener"));
                    handler = new AsyncInvoker(dispatcher);
                    break;
            }

            return (I) Reflects.newProxy(serviceInterface, handler);
        } finally {
            recycle();
        }
    }

    private static void checkValid(ProxyFactory factory) {
        if (updater.get(factory) == 1) {
            throw new IllegalStateException(
                    Reflects.simpleClassName(factory) + " is used by others, you should create another one!");
        }
    }

    private static final ConsumerHook logConsumerHook = new ConsumerHook() {

        @Override
        public void before(JRequest request) {
            logger.debug("Request: [{}], {}.", request.invokeId(), request.message());
        }

        @Override
        public void after(JRequest request) {
            logger.debug("Request: [{}], has respond.", request.invokeId());
        }
    };

    private ProxyFactory(Recyclers.Handle<ProxyFactory> handle) {
        this.handle = handle;
    }

    private boolean recycle() {
        // help GC
        client = null;
        addresses = null;
        serviceInterface = null;
        timeoutMills = 0;
        hooks = null;
        listener = null;

        return recyclers.recycle(this, handle);
    }

    private static final Recyclers<ProxyFactory> recyclers = new Recyclers<ProxyFactory>() {

        @Override
        protected ProxyFactory newObject(Handle<ProxyFactory> handle) {
            return new ProxyFactory(handle);
        }
    };

    private transient final Recyclers.Handle<ProxyFactory> handle;
}
