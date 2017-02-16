/**
 * 
 */
package com.lafaspot.pop.command;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * Pop command.
 * 
 * @author kraman
 *
 */
public class PopCommand {
    /** Command type. */
    private final Type type;
    /** Arguments. */
    private final List<String> args = new ArrayList<String>();

    /** Response. */
    private final PopCommandResponse response;

    /**
     * Constructor.
     * @param type command type
     */
    public PopCommand(@Nonnull final Type type) {
        this.type = type;
        this.response = new PopCommandResponse(this);
    }

    /**
     * Add optional arguments.
     * @param arg arguments
     */
    public void addArgs(@Nonnull final String arg) {
        args.add(arg);
    }

    /**
     * Get the command line for this command - the line to be sent over wire.
     * @return command line
     */
    public String getCommandLine() {
        final StringBuffer buf = new StringBuffer();
        buf.append(type.name());
        if (args.size() > 0) {
            for (final String arg : args) {
                buf.append(" ");
                buf.append(arg);
            }
        }
        buf.append("\r\n");
        return buf.toString();
    }

    /**
     * Get the command type.
     * @return command type
     */
    public Type getType() {
        return type;
    }

    /**
     * Get command response.
     * @return command response
     */
    public PopCommandResponse getResponse() {
        return response;
    }

    /**
     * Pop command type enum.
     * @author kraman
     *
     */
    public enum Type {
        /** Invalid command. */
        INVALID(0, false),
        /** User command. */
        USER(1, false),
        /** Pass command. */
        PASS(2, false),
        /** Stat command. */
        STAT(3, false),
        /** List command. */
        LIST(4, true),
        /** Retr command. */
        RETR(5, true),
        /** Dele command. */
        DELE(6, false),
        /** Noop command. */
        NOOP(7, false),
        /** Rset command. */
        RSET(8, false),
        /** Top comamnd. */
        TOP(9, true),
        /** Uidl command. */
        UIDL(10, true),
        /** Uidl command. */
        QUIT(11, false),
        /** Capa command. */
        CAPA(12, true),
        /** Auth command. */
        AUTH(13, true),
        /** Last command. */
        LAST(14, true);

        /** Is this command multiline. */
        private final boolean multiLine;
        /** Command type. */
        private final int type;

        /**
         * Constructor.
         * @param type command type
         * @param multiLine is this multiline command
         */
        Type(final int type, final boolean multiLine) {
            this.type = type;
            this.multiLine = multiLine;
        }

        /**
         * Get if this is a multiline command.
         * @return is this multiline command
         */
        public boolean multiLine() {
            return multiLine;
        }
    }

}
