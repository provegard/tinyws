# TinyWS

[![Build Status](https://travis-ci.org/provegard/tinyws.svg?branch=master)](https://travis-ci.org/provegard/tinyws)

## What is it?

A tiny WebSocket server written in Java 8.

## Why?

Because it's fun and I wanted to be able to have a WebSocket server without
several megabytes of dependencies.

## Status

Version 0.0.1.

It passes all tests of [Autobahn|Testsuite](https://github.com/crossbario/autobahn-testsuite) version
0.7.5, except 12.\* and 13.\* (compression using the permessage-deflate extension).

Features:

* Standards-compliant (according to AutoBahn|Testsuite at least)
* Tiny&mdash;one file
* NO external dependencies
* Requires Java 8
* Multiple endpoints
* Configurable Maximum frame size (controls fragmentation)
* Configurable address and port
* Configurable backlog
* Logging via simple interface&mdash;no dependency on any particilar log framework

Limitations:

* No HTTPS
* No frame compression support
* No extension support
* Maximum payload size is 0x7fffffff (2147483647) bytes
* Only talks protocol version 13 (mandated by [RFC 6455](https://tools.ietf.org/html/rfc6455))

## Running tests

    ./gradlew test
    
Note that the tests requires [wstest](https://github.com/crossbario/autobahn-testsuite) to be
installed and available on the path.
    
## Examples

The _echoserver_ folder contains an&mdash;drum roll&mdash;echo server!

## Usage/documentation

TBD

## Author

Per Rovegård

Twitter: @provegard

## License

MIT, https://per.mit-license.org/2017

See also the LICENSE file.

