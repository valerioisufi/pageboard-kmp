package io.github.valerioisufi.pageboard.agent

/** Why a tool did not do what it was asked, told apart for the client (e.g. by an exit code). */
public enum class AgentErrorCode(public val wireName: String) {
    /** The call itself is wrong: an unknown tool, a missing or malformed argument. */
    BAD_REQUEST("bad_request"),

    /** The access is turned off in the application. */
    ACCESS_OFF("access_off"),

    /** The user did not grant this document. */
    NOT_GRANTED("not_granted"),

    /** The document, page, text or proposal is not there. */
    NOT_FOUND("not_found"),

    /** Refused by the checks: too large, too many, a document that may only be read. */
    REFUSED("refused"),

    /** The application does not give what this tool needs. */
    UNAVAILABLE("unavailable")
}

/** A tool refusing a call: its [code] and a [message] saying what to do about it. */
public class AgentToolException(public val code: AgentErrorCode, message: String) : Exception(message)
