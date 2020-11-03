//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Destroyable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
 * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
 */
public class HttpInput extends ServletInputStream implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpInput.class);

    private final byte[] _oneByteBuffer = new byte[1];
    private final BlockingContentProducer _blockingContentProducer;
    private final AsyncContentProducer _asyncContentProducer;

    private final HttpChannelState _channelState;
    private ContentProducer _contentProducer;
    private boolean _consumedEof;
    private ReadListener _readListener;

    public HttpInput(HttpChannelState state)
    {
        _channelState = state;
        _asyncContentProducer = new AsyncContentProducer(state.getHttpChannel());
        _blockingContentProducer = new BlockingContentProducer(_asyncContentProducer);
        _contentProducer = _blockingContentProducer;
    }

    /* HttpInput */

    public void recycle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("recycle {}", this);
        _blockingContentProducer.recycle();
        _contentProducer = _blockingContentProducer;
        _consumedEof = false;
        _readListener = null;
    }

    /**
     * @return The current Interceptor, or null if none set
     */
    public Interceptor getInterceptor()
    {
        return _contentProducer.getInterceptor();
    }

    /**
     * Set the interceptor.
     *
     * @param interceptor The interceptor to use.
     */
    public void setInterceptor(Interceptor interceptor)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setting interceptor to {}", interceptor);
        _contentProducer.setInterceptor(interceptor);
    }

    /**
     * Set the {@link Interceptor}, chaining it to the existing one if
     * an {@link Interceptor} is already set.
     *
     * @param interceptor the next {@link Interceptor} in a chain
     */
    public void addInterceptor(Interceptor interceptor)
    {
        Interceptor currentInterceptor = _contentProducer.getInterceptor();
        if (currentInterceptor == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("adding single interceptor: {}", interceptor);
            _contentProducer.setInterceptor(interceptor);
        }
        else
        {
            ChainedInterceptor chainedInterceptor = new ChainedInterceptor(currentInterceptor, interceptor);
            if (LOG.isDebugEnabled())
                LOG.debug("adding chained interceptor: {}", chainedInterceptor);
            _contentProducer.setInterceptor(chainedInterceptor);
        }
    }

    public long getContentReceived()
    {
        return _contentProducer.getRawContentArrived();
    }

    public boolean consumeAll()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("consume all");
        boolean atEof = _contentProducer.consumeAll(new IOException("Unconsumed content"));
        if (atEof)
            _consumedEof = true;

        if (isFinished())
            return !isError();

        return false;
    }

    public boolean isError()
    {
        boolean error = _contentProducer.isError();
        if (LOG.isDebugEnabled())
            LOG.debug("isError = {}", error);
        return error;
    }

    public boolean isAsync()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("isAsync read listener = " + _readListener);
        return _readListener != null;
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        boolean finished = _consumedEof;
        if (LOG.isDebugEnabled())
            LOG.debug("isFinished? {}", finished);
        return finished;
    }

    @Override
    public boolean isReady()
    {
        boolean ready = _contentProducer.isReady();
        if (!ready)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("isReady? false");
            return false;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("isReady? true");
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setting read listener to {}", readListener);
        if (_readListener != null)
            throw new IllegalStateException("ReadListener already set");
        _readListener = Objects.requireNonNull(readListener);
        //illegal if async not started
        if (!_channelState.isAsyncStarted())
            throw new IllegalStateException("Async not started");

        _contentProducer = _asyncContentProducer;
        // trigger content production
        if (isReady() && _channelState.onReadEof()) // onReadEof b/c we want to transition from WAITING to WOKEN
            scheduleReadListenerNotification(); // this is needed by AsyncServletIOTest.testStolenAsyncRead
    }

    public boolean onContentProducible()
    {
        return _contentProducer.onContentProducible();
    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        if (read == 0)
            throw new IOException("unready read=0");
        return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        // Calculate minimum request rate for DoS protection
        _contentProducer.checkMinDataRate();

        Content content = _contentProducer.nextContent();
        if (content == null)
            throw new IllegalStateException("read on unready input");
        if (!content.isSpecial())
        {
            int read = content.get(b, off, len);
            if (LOG.isDebugEnabled())
                LOG.debug("read produced {} byte(s)", read);
            if (content.isEmpty())
                _contentProducer.reclaim(content);
            return read;
        }

        Throwable error = content.getError();
        if (LOG.isDebugEnabled())
            LOG.debug("read error = " + error);
        if (error != null)
        {
            if (error instanceof IOException)
                throw (IOException)error;
            throw new IOException(error);
        }

        if (content.isEof())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("read at EOF, setting consumed EOF to true");
            _consumedEof = true;
            // If EOF do we need to wake for allDataRead callback?
            if (onContentProducible())
                scheduleReadListenerNotification();
            return -1;
        }

        throw new AssertionError("no data, no error and not EOF");
    }

    private void scheduleReadListenerNotification()
    {
        HttpChannel channel = _channelState.getHttpChannel();
        channel.execute(channel);
    }

    /**
     * Check if this HttpInput instance has content stored internally, without fetching/parsing
     * anything from the underlying channel.
     * @return true if the input contains content, false otherwise.
     */
    public boolean hasContent()
    {
        // Do not call _contentProducer.available() as it calls HttpChannel.produceContent()
        // which is forbidden by this method's contract.
        boolean hasContent = _contentProducer.hasContent();
        if (LOG.isDebugEnabled())
            LOG.debug("hasContent = {}", hasContent);
        return hasContent;
    }

    @Override
    public int available()
    {
        int available = _contentProducer.available();
        if (LOG.isDebugEnabled())
            LOG.debug("available = {}", available);
        return available;
    }

    /* Runnable */

    /*
     * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
     * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
     */
    @Override
    public void run()
    {
        Content content = _contentProducer.nextContent();
        if (LOG.isDebugEnabled())
            LOG.debug("running on content {}", content);
        // The nextContent() call could return null if the transformer ate all
        // the raw bytes without producing any transformed content.
        if (content == null)
            return;

        // This check is needed when a request is started async but no read listener is registered.
        if (_readListener == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running without a read listener");
            onContentProducible();
            return;
        }

        if (content.isSpecial())
        {
            Throwable error = content.getError();
            if (error != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running has error: {}", (Object)error);
                // TODO is this necessary to add here?
                _channelState.getHttpChannel().getResponse().getHttpFields().add(HttpConnection.CONNECTION_CLOSE);
                _readListener.onError(error);
            }
            else if (content.isEof())
            {
                try
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("running at EOF");
                    _readListener.onAllDataRead();
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("running failed onAllDataRead", x);
                    _readListener.onError(x);
                }
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running has content");
            try
            {
                _readListener.onDataAvailable();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running failed onDataAvailable", x);
                _readListener.onError(x);
            }
        }
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "@" + hashCode() +
            " cs=" + _channelState +
            " cp=" + _contentProducer +
            " eof=" + _consumedEof;
    }

    public interface Interceptor
    {
        /**
         * @param content The content to be intercepted.
         * The content will be modified with any data the interceptor consumes, but there is no requirement
         * that all the data is consumed by the interceptor.
         * @return The intercepted content or null if interception is completed for that content.
         */
        Content readFrom(Content content);
    }

    /**
     * An {@link Interceptor} that chains two other {@link Interceptor}s together.
     * The {@link Interceptor#readFrom(Content)} calls the previous {@link Interceptor}'s
     * {@link Interceptor#readFrom(Content)} and then passes any {@link Content} returned
     * to the next {@link Interceptor}.
     */
    private static class ChainedInterceptor implements Interceptor, Destroyable
    {
        private final Interceptor _prev;
        private final Interceptor _next;

        ChainedInterceptor(Interceptor prev, Interceptor next)
        {
            _prev = prev;
            _next = next;
        }

        Interceptor getPrev()
        {
            return _prev;
        }

        Interceptor getNext()
        {
            return _next;
        }

        @Override
        public Content readFrom(Content content)
        {
            Content c = getPrev().readFrom(content);
            if (c == null)
                return null;
            return getNext().readFrom(c);
        }

        @Override
        public void destroy()
        {
            if (_prev instanceof Destroyable)
                ((Destroyable)_prev).destroy();
            if (_next instanceof Destroyable)
                ((Destroyable)_next).destroy();
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "@" + hashCode() + " [p=" + _prev + ",n=" + _next + "]";
        }
    }

    /**
     * A content represents the production of a {@link HttpChannel} returned by {@link HttpChannel#produceContent()}.
     * There are two fundamental types of content: special and non-special.
     * Non-special content always wraps a byte buffer that can be consumed and must be recycled once it is empty, either
     * via {@link #succeeded()} or {@link #failed(Throwable)}.
     * Special content indicates a special event, like EOF or an error and never wraps a byte buffer. Calling
     * {@link #succeeded()} or {@link #failed(Throwable)} on those have no effect.
     */
    public static class Content implements Callback
    {
        protected final ByteBuffer _content;

        public Content(ByteBuffer content)
        {
            _content = content;
        }

        /**
         * Get the wrapped byte buffer. Throws {@link IllegalStateException} if the content is special.
         * @return the wrapped byte buffer.
         */
        public ByteBuffer getByteBuffer()
        {
            return _content;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        /**
         * Read the wrapped byte buffer. Throws {@link IllegalStateException} if the content is special.
         * @param buffer The array into which bytes are to be written.
         * @param offset The offset within the array of the first byte to be written.
         * @param length The maximum number of bytes to be written to the given array.
         * @return The amount of bytes read from the buffer.
         */
        public int get(byte[] buffer, int offset, int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.get(buffer, offset, length);
            return length;
        }

        /**
         * Skip some bytes from the buffer. Has no effect on a special content.
         * @param length How many bytes to skip.
         * @return How many bytes were skipped.
         */
        public int skip(int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.position(_content.position() + length);
            return length;
        }

        /**
         * Check if there is at least one byte left in the buffer.
         * Always false on a special content.
         * @return true if there is at least one byte left in the buffer.
         */
        public boolean hasContent()
        {
            return _content.hasRemaining();
        }

        /**
         * Get the number of bytes remaining in the buffer.
         * Always 0 on a special content.
         * @return the number of bytes remaining in the buffer.
         */
        public int remaining()
        {
            return _content.remaining();
        }

        /**
         * Check if the buffer is empty.
         * Always true on a special content.
         * @return true if there is 0 byte left in the buffer.
         */
        public boolean isEmpty()
        {
            return !_content.hasRemaining();
        }

        /**
         * Check if the content is special.
         * @return true if the content is special, false otherwise.
         */
        public boolean isSpecial()
        {
            return false;
        }

        /**
         * Check if EOF was reached. Both special and non-special content
         * can have this flag set to true but in the case of non-special content,
         * this can be interpreted as a hint as it is always going to be followed
         * by another content that is both special and EOF.
         * @return true if EOF was reached, false otherwise.
         */
        public boolean isEof()
        {
            return false;
        }

        /**
         * Get the reported error. Only special contents can have an error.
         * @return the error or null if there is none.
         */
        public Throwable getError()
        {
            return null;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s,spc=%s,eof=%s,err=%s}", getClass().getSimpleName(), hashCode(),
                BufferUtil.toDetailString(_content), isSpecial(), isEof(), getError());
        }
    }

    /**
     * Simple non-special content wrapper allow overriding the EOF flag.
     */
    public static class WrappingContent extends Content
    {
        private final Content _delegate;
        private final boolean _eof;

        public WrappingContent(Content delegate, boolean eof)
        {
            super(delegate.getByteBuffer());
            _delegate = delegate;
            _eof = eof;
        }

        @Override
        public boolean isEof()
        {
            return _eof;
        }

        @Override
        public void succeeded()
        {
            _delegate.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            _delegate.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _delegate.getInvocationType();
        }
    }

    /**
     * Abstract class that implements the standard special content behavior.
     */
    public abstract static class SpecialContent extends Content
    {
        public SpecialContent()
        {
            super(null);
        }

        @Override
        public final ByteBuffer getByteBuffer()
        {
            throw new IllegalStateException(this + " has no buffer");
        }

        @Override
        public final int get(byte[] buffer, int offset, int length)
        {
            throw new IllegalStateException(this + " has no buffer");
        }

        @Override
        public final int skip(int length)
        {
            return 0;
        }

        @Override
        public final boolean hasContent()
        {
            return false;
        }

        @Override
        public final int remaining()
        {
            return 0;
        }

        @Override
        public final boolean isEmpty()
        {
            return true;
        }

        @Override
        public final boolean isSpecial()
        {
            return true;
        }
    }

    /**
     * EOF special content.
     */
    public static final class EofContent extends SpecialContent
    {
        @Override
        public boolean isEof()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName();
        }
    }

    /**
     * Error special content.
     */
    public static final class ErrorContent extends SpecialContent
    {
        private final Throwable _error;

        public ErrorContent(Throwable error)
        {
            _error = error;
        }

        @Override
        public Throwable getError()
        {
            return _error;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + " [" + _error + "]";
        }
    }
}
