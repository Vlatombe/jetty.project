package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpChannel;

public class AsyncContextState implements AsyncContext
{
    private final HttpChannel _channel;
    volatile ServletRequestState _state;

    public AsyncContextState(ServletRequestState state)
    {
        _state = state;
        _channel = _state.getHttpChannel();
    }

    public HttpChannel getHttpChannel()
    {
        return _channel;
    }

    ServletRequestState state()
    {
        ServletRequestState state = _state;
        if (state == null)
            throw new IllegalStateException("AsyncContext completed and/or Request lifecycle recycled");
        return state;
    }

    @Override
    public void addListener(final AsyncListener listener, final ServletRequest request, final ServletResponse response)
    {
        AsyncListener wrap = new WrappedAsyncListener(listener, request, response);
        state().addListener(wrap);
    }

    @Override
    public void addListener(AsyncListener listener)
    {
        state().addListener(listener);
    }

    @Override
    public void complete()
    {
        state().complete();
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException
    {
        // TODO: Use ServletContextHandler createInstance use DecoratedObjectFactory.
        try
        {
            return clazz.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public void dispatch()
    {
        state().dispatch(null, null);
    }

    @Override
    public void dispatch(String path)
    {
        state().dispatch(null, path);
    }

    @Override
    public void dispatch(ServletContext context, String path)
    {
        state().dispatch(context, path);
    }

    @Override
    public ServletRequest getRequest()
    {
        return state().getAsyncContextEvent().getSuppliedRequest();
    }

    @Override
    public ServletResponse getResponse()
    {
        return state().getAsyncContextEvent().getSuppliedResponse();
    }

    @Override
    public long getTimeout()
    {
        return state().getTimeout();
    }

    @Override
    public boolean hasOriginalRequestAndResponse()
    {
        ServletRequest servletRequest = getRequest();
        ServletResponse servletResponse = getResponse();
        ServletChannel servletChannel = _state.getServletChannel();
        HttpServletRequest originalHttpServletRequest = servletChannel.getRequest().getHttpServletRequest();
        HttpServletResponse originalHttpServletResponse = servletChannel.getResponse().getHttpServletResponse();
        return (servletRequest == originalHttpServletRequest && servletResponse == originalHttpServletResponse);
    }

    @Override
    public void setTimeout(long timeout)
    {
        state().setTimeout(timeout);
    }

    @Override
    public void start(final Runnable task)
    {
        _state.getServletChannel().getContext().execute(() ->
        {
            AsyncContextEvent asyncContextEvent = state().getAsyncContextEvent();
            if (asyncContextEvent != null && asyncContextEvent.getContext() != null)
            {
                asyncContextEvent.getContext().run(task);
            }
            else
            {
                task.run();
            }
        });
    }

    public void reset()
    {
        _state = null;
    }

    public ServletRequestState getServletChannelState()
    {
        return state();
    }

    public static class WrappedAsyncListener implements AsyncListener
    {
        private final AsyncListener _listener;
        private final ServletRequest _request;
        private final ServletResponse _response;

        public WrappedAsyncListener(AsyncListener listener, ServletRequest request, ServletResponse response)
        {
            _listener = listener;
            _request = request;
            _response = response;
        }

        public AsyncListener getListener()
        {
            return _listener;
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            _listener.onTimeout(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            _listener.onStartAsync(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            _listener.onError(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            _listener.onComplete(new AsyncEvent(event.getAsyncContext(), _request, _response, event.getThrowable()));
        }
    }
}
