package com.bennod.pkmninstaller.shizuku

/**
 * Result of running a command through Shizuku.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Runs shell commands as the `shell` user (uid 2000) via a Shizuku
 * [UserService].
 *
 * This is the Phase 2 building block: it proves we can execute commands with
 * shell privileges, which is what the install engine (Phase 3) builds on.
 *
 * The user service is bound lazily; [bind] must be called (and the connection
 * established) before [run] can be used.
 */
class ShellRunner(
    onConnected: () -> Unit = {},
    onDisconnected: () -> Unit = {},
) {

    /** The underlying client, shared with the install engine. */
    val client = UserServiceClient(onConnected, onDisconnected)

    val isBound: Boolean get() = client.isBound

    /** Starts binding to the user service. */
    fun bind() = client.bind()

    /** Unbinds from the user service. */
    fun unbind() = client.unbind()

    /**
     * Runs [command] through `sh -c` in the user service and returns its output.
     *
     * @throws IllegalStateException if the user service is not bound.
     */
    fun run(command: String): ShellResult = client.exec(command)
}
