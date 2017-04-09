package com.programmaticallyspeaking.tinyws;

@FunctionalInterface
interface ThrowingConsumer<T> {
    void accept(T t) throws Throwable;
}
