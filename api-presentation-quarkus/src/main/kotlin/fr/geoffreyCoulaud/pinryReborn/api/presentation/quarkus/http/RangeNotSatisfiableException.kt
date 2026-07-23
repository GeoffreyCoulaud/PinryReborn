package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.http

/**
 * Thrown by [RangeHeader.parse] when the requested range's start lies at or past the end of the
 * resource (spec `docs/specs/2026-07-22-user-data-export.md` §7): `416 Requested Range Not
 * Satisfiable`, with [totalSize] carried to build the `Content-Range` header (`bytes star/<total>`,
 * literally `bytes` + asterisk + `/<total>`; spelled out here so the KDoc block comment does not
 * see its own closing `&#42;/` early).
 */
class RangeNotSatisfiableException(val totalSize: Long) : RuntimeException("Range not satisfiable")
