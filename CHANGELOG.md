# Changes

## 0.0.3 (2017-03-12)

* Don't use a separate Executor for invoking handlers&mdash;async handler invocation created concurrency problems.
* Performance & memory improvements
* WebSocketHandler.onTextMessage received a `CharSequence` instead of a `String`.

## 0.0.2 (2017-03-11)

* Set TCP_NODELAY (disable Nagle's algorithm) since otherwise Autobahn tests fail/hang on Linux.

## 0.0.1 (2017-03-09)

* First released version
