# core:ismart

A Kotlin client for MG's i-SMART service in India: sign-in, the vehicle list, status and charging frames, and
PIN-confirmed remote commands.

## Credit

This module is a Kotlin port of [mg-ismart-india-client](https://github.com/john-lazarus/mg-ismart-india-client)
by **John Lazarus**, a Python client that worked out how the MG India app talks to MG's servers: request signing,
the TAP message format, the login frame and the status fields. Volta's car features are built on that work.
Thank you, John.

The original is MIT-licensed; its licence text is in [NOTICE.md](../../NOTICE.md). The port is pure Kotlin
(OkHttp, kotlinx.serialization, coroutines), with golden-vector tests checked against the Python client's output.
