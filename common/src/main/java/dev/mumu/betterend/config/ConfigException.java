package dev.mumu.betterend.config;

/**
 * Thrown when a packaged config file parses but a value cannot be used -- a key of the wrong type, or
 * an unreadable loopback from {@link BetterEndConfig}.
 * <p>
 * This is a deliberate stop, but be honest about its reach: it makes the plugin NOT enable. It cannot
 * stop Paper/Folia from falling back to the vanilla generator for a world that named BetterEnd, which
 * still writes terrain the owner did not intend. So the only safe owner action is to stop the server
 * and fix the reported keys before any such world is generated -- which is what the message says. The
 * owner's own file is left untouched.
 */
public final class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
