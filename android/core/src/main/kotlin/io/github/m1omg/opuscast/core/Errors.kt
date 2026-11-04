package io.github.m1omg.opuscast.core

/** Something the TV (or a file) didn't do. [reason] picks the message the app shows; [detail] is
 *  the raw text from the TV or the system, shown in brackets. */
class TvException(val reason: Reason, val detail: String = "", cause: Throwable? = null) :
    Exception(if (detail.isEmpty()) reason.name else "${reason.name}: $detail", cause) {

    enum class Reason {
        /** The device has no AVTransport service. */
        CANT_PLAY_MEDIA,
        /** The TV lacks the service or event the command needs ([detail] names it). */
        UNSUPPORTED,
        /** The TV answered with an error ([detail] is "Action: description"). */
        REFUSED,
        /** No answer from the TV. */
        UNREACHABLE,
        /** No free port for the file server. */
        NO_PORT,
        /** The subtitle file couldn't be read. */
        SUBTITLE_UNREADABLE,
        /** The subtitle format isn't one OpusCast can convert, or the file is damaged. */
        SUBTITLE_BAD,
        /** The video file couldn't be read. */
        FILE_UNREADABLE,
    }
}
