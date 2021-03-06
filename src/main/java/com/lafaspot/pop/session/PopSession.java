/**
 *
 */
package com.lafaspot.pop.session;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;

import com.lafaspot.logfast.logging.Logger;
import com.lafaspot.pop.command.PopCommand;
import com.lafaspot.pop.command.PopCommandResponse;
import com.lafaspot.pop.exception.PopException;
import com.lafaspot.pop.exception.PopException.Type;
import com.lafaspot.pop.netty.PopMessageDecoder;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * The PopSession object, used to conenct and send commands to the server.
 * @author kraman
 *
 */
public class PopSession {

	/** Future for the current command being executed. */
	private PopFuture<PopCommandResponse> currentCommandFuture;

	/** State of the sesson.*/
    private final AtomicReference<State> stateRef;

    /** Netty bootstrap object. */
    private final Bootstrap bootstrap;

    /** The logger object. */
    private final Logger logger;

    /** The Netty session object. */
    private Channel sessionChannel;

    /** The current command being executed. */
    private AtomicReference<PopCommand> currentCommandRef = new AtomicReference<PopCommand>();
    /** The SslContext object. */
    private final SslContext sslContext;
    /** Max line length. */
    private static final int MAX_LINE_LENGTH = 8192;

    /** 
     * Constructor for PopSession, used to communicate with a POP server.
     * @param sslContext the ssl context object
     * @param bootstrap the Netty bootstrap object
     * @param logger the logger object
     */
    public PopSession(@Nonnull final SslContext sslContext, @Nonnull final Bootstrap bootstrap, @Nonnull final Logger logger) {
        this.sslContext = sslContext;
        this.bootstrap = bootstrap;
        this.logger = logger;
        this.stateRef = new AtomicReference<>(State.NULL);
    }

    /**
     * Connect to the specified POP server.
     * @param server the server to connect to
     * @param port to connect to
     * @param connectTimeout timeout value
     * @param inactivityTimeout timeout value
     * @return future object for connect
     * @throws PopException on failure
     */
    public PopFuture<PopCommandResponse> connect(@Nonnull final String server,
    		final int port, final int connectTimeout, final int inactivityTimeout) throws PopException {
        logger.debug(" +++ connect to  " + server, null);



        final PopSession thisSession = this;
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout);

        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();

                SslContext ctx2 = SslContextBuilder.forClient().build();
            	p.addLast("ssl", ctx2.newHandler(ch.alloc(), server, port));
                p.addLast("inactivityHandler", new PopInactivityHandler(thisSession, inactivityTimeout, logger));
                p.addLast(new DelimiterBasedFrameDecoder(MAX_LINE_LENGTH, Delimiters.lineDelimiter()));
                p.addLast(new StringDecoder());
                p.addLast(new StringEncoder());
                p.addLast(new PopMessageDecoder(thisSession, logger));
            }

        });

        final PopCommand cmd = new PopCommand(PopCommand.Type.INVALID);
        ChannelFuture future;
        try {
            future = bootstrap.connect(server, port).sync();
        } catch (InterruptedException e) {
            throw new PopException(Type.CONNECT_FAILURE, e);
        }

        stateRef.compareAndSet(State.NULL, State.COMMAND_SENT);
        sessionChannel = future.channel();
        currentCommandFuture = new PopFuture<PopCommandResponse>(future);
        currentCommandRef.set(cmd);
        future.addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(final Future<? super Void> future) throws Exception {
                if (future.isSuccess()) {
                	currentCommandFuture.done(new PopCommandResponse(cmd));
                	/*
                    if (!stateRef.compareAndSet(State.CONNECT_SENT, State.WAIT_FOR_OK)) {
                        logger.error("Connect success in invalid state " + stateRef.get().name(), null);
                        return;
                    }
                    */
                }
            }
        });

        return currentCommandFuture;
    }

    /** 
     * Send a POP command to the server.
     * @param command the command to send to server
     * @return the future object for this command
     * @throws PopException on failure
     */
    public PopFuture<PopCommandResponse> execute(@Nonnull final PopCommand command) throws PopException {

        if (!stateRef.compareAndSet(State.CONNECTED, State.COMMAND_SENT)) {
            throw new PopException(PopException.Type.INVALID_STATE);
        }

        if (!currentCommandRef.compareAndSet(null, command)) {
            throw new PopException(PopException.Type.INVALID_STATE);
        }

        final StringBuilder commandToWrite = new StringBuilder();
        commandToWrite.append(command.getCommandLine());


        Future f = sessionChannel.writeAndFlush(commandToWrite.toString());
        currentCommandFuture = new PopFuture<PopCommandResponse>(f);
        return currentCommandFuture;
    }

    /**
     * Disconnect the session, close session and cleanup.
     * @return the future object for disconnect
     * @throws PopException on failure
     */
    public PopFuture<PopCommandResponse> disconnect() throws PopException {
    	final State state = stateRef.get();
    	if (state == State.NULL) {
            throw new PopException(PopException.Type.INVALID_STATE);
    	}

		if (stateRef.compareAndSet(state, State.NULL)) {
			Future f = sessionChannel.disconnect();
			currentCommandFuture = new PopFuture<>(f);
			sessionChannel = null;
			return currentCommandFuture;
		}

        throw new PopException(PopException.Type.INVALID_STATE);
    }

    /**
     * Callback from netty on channel inactivity.
     */
    public void onTimeout() {
        logger.debug("**channel timeout** TH " + Thread.currentThread().getId(), null);

    }

    /**
     * Called when response message is being received from the server. Delimiter is \r\n.
     * @param line the response line
     */
    public void onMessage(final String line) {
    	final PopCommand command = currentCommandRef.get();
    	if (null == command) {
    		// bad
    		return;
    	}

		command.getResponse().parse(line);
    	if (command.getResponse().parseComplete()) {
    		if (!stateRef.compareAndSet(State.COMMAND_SENT, State.CONNECTED)) {
				currentCommandFuture.done(new PopException(Type.INTERNAL_FAILURE));
				return;
    		}
			if (currentCommandRef.compareAndSet(command, null)) {
				currentCommandFuture.done(command.getResponse());
			} else {
				currentCommandFuture.done(new PopException(Type.INTERNAL_FAILURE));
			}
    	}
    }

    /**
     * States of PopSession.
     * @author kraman
     *
     */
    public enum State {
    	/** Null session not connected. */
        NULL,
        /** Session is connected, ready to accept commands. */
        CONNECTED,
        /** Command is just being executed, waiting for response. */
        COMMAND_SENT
    }
}
