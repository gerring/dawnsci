/*-
 *******************************************************************************
 * Copyright (c) 2011, 2016 Diamond Light Source Ltd.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Matthew Gerring - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.dawnsci.remotedataset.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.january.DatasetException;
import org.eclipse.january.dataset.DataEvent;
import org.eclipse.january.dataset.Dataset;
import org.eclipse.january.dataset.IDataListener;
import org.eclipse.january.dataset.IDatasetConnector;
import org.eclipse.january.dataset.IDynamicDataset;
import org.eclipse.january.dataset.LazyWriteableDataset;
import org.eclipse.january.dataset.ShapeUtils;
import org.eclipse.january.metadata.DynamicConnectionInfo;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages a remote connection to data and
 * allows datasets to work seamlessly remotely as if
 * they were ILazyDatasets.
 * 
 * To do this they require to know the data server's name and port.
 * They also need a path on the server for the file which they are linked to.
 * 
 * The DataServer is a standard HTTP with web sockets for events. This makes
 * RemoteDataset able to pass over HTTP with port 80 open for external data 
 * viewing too.
 * 
 * 
<usage><code>
final IRemoteDatasetService service = ...
final IRemoteDataset data = service.createRemoteDataset("localhost", 8080);<br>
data.setPath(h5File.getAbsolutePath());
data.setDatasetName("image"); // We just get the first image in the PNG file.
data.connect();

try {
    // Use it the same way as ILazyDataset
} finally {
    data.disconnect();
}
</code></usage>
 * 
 * @author Matthew Gerring
 *
 */
class RemoteDataset extends LazyWriteableDataset implements IDatasetConnector {
	
	private static final Logger logger = LoggerFactory.getLogger(RemoteDataset.class);
	
	private final URLBuilder urlBuilder;
	
	// Web socket stuff
	private Session connection;

	private boolean dynamicShape = true;
	private int[] transShape;

	private Executor exec;

	private WebSocketClient client;

	/**
	 * 
	 */
	private static final long serialVersionUID = -9031675045219778735L;

	/**
	 * 
		<usage><code>
		final IRemoteDatasetService service = ...
		final IRemoteDataset data = service.createRemoteDataset("localhost", 8080);<br>
		data.setPath(h5File.getAbsolutePath());<br>
		data.setDataset("image"); // We just get the first image in the PNG file.<br>
		data.connect();<br>
		<br>
		try {<br>
		    // Use it the same way as ILazyDataset<br>
		} finally {<br>
		    data.disconnect();<br>
		}<br>
		</code></usage>
	 * @param serverName
	 * @param port
	 */
	public RemoteDataset(String serverName, int port, Executor exec) {
		super("unknown", Dataset.INT, new int[]{1}, new int[]{IDynamicDataset.UNLIMITED}, null, null);
		this.urlBuilder = new URLBuilder(serverName, port);
		urlBuilder.setWritingExpected(true);
		this.exec       = exec;
		this.loader = new RemoteLoader(urlBuilder);
	}

	@Override
	public String connect() throws DatasetException {
		return connect(500, TimeUnit.MILLISECONDS);
	}

	/**
	 * Call to read the dataset, set current shape and create event connnection for
	 * IDynamicDataset part of the dataset
	 */
	@Override
	public String connect(long time, TimeUnit unit) throws DatasetException {
		try {
			createInfo();
			if (eventDelegate.hasDataListeners()) {
				createFileListener();
			}
		} catch (Exception e) {
			throw new DatasetException(e);
		}
		
		// TODO Does this cause a memory leak?
		// If multiple connect/disconnect are called will this break things?
		addMetadata(new DynamicConnectionInfo() {
			private static final long serialVersionUID = 6220818379127865903L;
			public boolean isConnected() {
				return connection.isOpen();
			}
		});
		
		return null;
	}

	public void disconnect() throws DatasetException {

		eventDelegate.clear();
		try {
			if (connection != null && connection.isOpen()) {
				connection.getRemote().sendString("Disconnected from " + urlBuilder.getPath());
				connection.close();
			}
			if (client != null && client.isStarted()) {
				client.stop();
			}
		} catch (Exception e) {
			throw new DatasetException(e);
		}
	}
	
    @Override
    public boolean refreshShape() {
    	try {
			createInfo();
		} catch (Exception e) {
			e.printStackTrace();
		}
    	return true;
    }
    
	private void createFileListener() throws Exception {
		
        URI uri = URI.create(urlBuilder.getEventURL());

        this.client = new WebSocketClient(exec);
        client.start();
       
        final DataEventSocket clientSocket = new DataEventSocket();
        // Attempt Connect
        Future<Session> fut = client.connect(clientSocket, uri);

        // Wait for Connect
        connection = fut.get();

        // Send a message
        connection.getRemote().sendString("Connected to "+urlBuilder.getPath());
	}
	
	public class DataEventSocket extends WebSocketAdapter {
		@Override
	    public void onWebSocketText(String data) {
			
	        super.onWebSocketText(data);			
	        try {
				DataEvent evt = DataEvent.decode(data);
				if (evt.getShape()!=null) {
					if (dynamicShape) {
						resize(evt.getShape());
						setMax(evt.getShape());
						
						eventDelegate.fire(evt);
					} else {
						RemoteDataset.this.transShape =  evt.getShape();
					}
				}
			} catch (Exception ne) {
				logger.error("Cannot set shape of dataset!", ne);
			}
		}
	}
	
	public void setShapeDynamic(boolean isDyn) {
		dynamicShape  = isDyn;
		if (dynamicShape && transShape!=null) {
		    this.shape = transShape;
		    setMax(shape);
		    transShape = null;
		}
	}

	private void setMax(int[] shape) {
		int[] max = new int[shape.length];
		for (int i = 0; i < max.length; i++) max[i] = -1;
		setMaxShape(max);
	}

	private void createInfo() throws Exception {
		
		List<String> info = getInfo();
		
		if (this.name == null) this.name  = info.get(0);
		this.shape = toIntArray(info.get(1));
		setMax(shape);
		this.oShape = shape;
		this.dtype = Integer.parseInt(info.get(2));
		this.isize = Integer.parseInt(info.get(3));
		try {
			size = ShapeUtils.calcLongSize(shape);
		} catch (IllegalArgumentException e) {
			size = Long.MAX_VALUE; // this indicates that the entire dataset cannot be read in! 
		}

		if (info.size() > 4) {
			setMaxShape(toIntArray(info.get(4)));
			setChunking(toIntArray(info.get(5)));
		}
	}

	private int[] toIntArray(String array) {
		// array is null, or of the form [1,2,3,4]
		if (array.equals("null"))
			return null;
		if (array.length() <= 2) {
			return new int[0]; // special case of scalar dataset
		}

		final String[] split = array.substring(1, array.length()-1).split(",");
		final int[]    ret   = new int[split.length];
		for (int i = 0; i < split.length; i++) {
			ret[i] = Integer.parseInt(split[i].trim());
 		}
		return ret;
	}

	private List<String> getInfo() throws Exception {
		
		final List<String> ret = new ArrayList<String>();
		final URL url = new URL(urlBuilder.getInfoURL());
		URLConnection  conn = url.openConnection();

		final BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
		try {
			String line = null;
			while((line = reader.readLine())!=null) ret.add(line);
		} finally {
			reader.close();
		}
		return ret;
	}

	@Override
	public void addDataListener(IDataListener l) {
		// If we are not already web socket client and connect has been called, create the listener.
		try {
		    if (this.connection==null && loader!=null) createFileListener();
		} catch (Exception ne) {
			throw new IllegalArgumentException(ne);
		}
		eventDelegate.addDataListener(l);
	}

	public String getPath() {
		return urlBuilder.getPath();
	}

	public void setPath(String path) {
		urlBuilder.setPath(path);
	}

	public String getDatasetName() {
		return urlBuilder.getDataset();
	}

	public void setDatasetName(String dataset) {
		urlBuilder.setDataset(dataset);
	}

	public boolean isWritingExpected() {
		return urlBuilder.isWritingExpected();
	}

	public void setWritingExpected(boolean writingExpected) {
		urlBuilder.setWritingExpected(writingExpected);
	}

	@Override
	public IDynamicDataset getDataset() {
		return this;
	}
	
	@Override
	public RemoteDataset clone() {
		RemoteDataset ret = new RemoteDataset(urlBuilder.getServerName(),urlBuilder.getPort(),this.exec);
		ret.urlBuilder.setDataset(this.urlBuilder.getDataset());
		ret.urlBuilder.setPath(this.urlBuilder.getPath());
		ret.client = this.client;
		ret.connection = this.connection;
		ret.loader = this.loader;
		ret.shape = shape;
		ret.size = size;
		ret.maxShape = maxShape;
		ret.oShape = oShape;
		ret.prepShape = prepShape;
		ret.postShape = postShape;
		ret.begSlice = begSlice;
		ret.delSlice = delSlice;
		ret.map = map;
		ret.base = base;
		ret.metadata = copyMetadata();
		ret.oMetadata = oMetadata;
		ret.eventDelegate = eventDelegate;
		ret.name = this.name;
		return ret;
	}
}
