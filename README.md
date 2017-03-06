# TinyWS

## What is it?

A tiny WebSocket server written in Java 8.

## Why?

Because it's fun and I wanted to be able to have a WebSocket server without
several megabytes of dependencies.

## Status

Version 0.0.1.

It passes all tests of [Autobahn|Testsuite](https://github.com/crossbario/autobahn-testsuite) version
0.10.9, except 12.\* and 13.\* (compression using the permessage-deflate extension).

Features:

* Standards-compliant (according to AutoBahn|Testsuite at least)
* Configurable address and port
* Tiny - one file
* NO external dependencies
* Requires Java 8
* Multiple endpoints
* Maximum frame size can be configured

Limitations:

* No HTTPS
* No frame compression support
* Maximum payload size is 0x7fffffff (2147483647) bytes

## Author

Per Rovegård, @provegard

## License

MIT, https://per.mit-license.org/2017

See also the LICENSE file.

