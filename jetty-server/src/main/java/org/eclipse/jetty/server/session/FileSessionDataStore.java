//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.server.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * FileSessionDataStore
 *
 * A file-based store of session data.
 */
public class FileSessionDataStore extends AbstractSessionDataStore
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    private File _storeDir;
    private boolean _deleteUnrestorableFiles = false;
    


    @Override
    protected void doStart() throws Exception
    {
        initializeStore();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    public File getStoreDir()
    {
        return _storeDir;
    }

    public void setStoreDir(File storeDir)
    {
        checkStarted();
        _storeDir = storeDir;
    }

    public boolean isDeleteUnrestorableFiles()
    {
        return _deleteUnrestorableFiles;
    }

    public void setDeleteUnrestorableFiles(boolean deleteUnrestorableFiles)
    {
        checkStarted();
        _deleteUnrestorableFiles = deleteUnrestorableFiles;
    }

 

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {   
        File file = null;
        if (_storeDir != null)
        {
            file = new File(_storeDir, getFileName(id));
            if (file.exists() && file.getParentFile().equals(_storeDir))
            {
                file.delete();
                return true;
            }
        }
         
        return false;
    }


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(Set, int)
     */
    @Override
    public Set<String> doGetExpired(Set<String> candidates, int expiryTimeoutSec)
    {
        //we don't want to open up each file and check, so just leave it up to the SessionStore
        //TODO as the session manager is likely to be a lazy loader, if a session is never requested, its
        //file will stay forever after a restart
        return candidates;
    }



    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {  
        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        Runnable r = new Runnable()
        {
            public void run ()
            {
                File file = new File(_storeDir,getFileName(id));

                if (!file.exists())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No file: {}",file);
                    return;
                }

                try (FileInputStream in = new FileInputStream(file))
                {
                    SessionData data = load(in);
                    //delete restored file
                    file.delete();
                    reference.set(data);
                }
                catch (UnreadableSessionDataException e)
                {
                    if (isDeleteUnrestorableFiles() && file.exists() && file.getParentFile().equals(_storeDir));
                    {
                        file.delete();
                        LOG.warn("Deleted unrestorable file for session {}", id);
                    }
                    exception.set(e);
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };
        //ensure this runs with the context classloader set
        _context.run(r);
        
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }
    
        

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
     */
    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        File file = null;
        if (_storeDir != null)
        {
            file = new File(_storeDir, getFileName(id));
            if (file.exists())
                file.delete();

            try(FileOutputStream fos = new FileOutputStream(file,false))
            {
                save(fos, id, data);
            }
            catch (Exception e)
            { 
                if (file != null) 
                    file.delete(); // No point keeping the file if we didn't save the whole session
                throw new UnwriteableSessionDataException(id, _context,e);             
            }
        }
    }
    
    /**
     * 
     */
    public void initializeStore ()
    {
        if (_storeDir == null)
            throw new IllegalStateException("No file store specified");

        if (!_storeDir.exists())
            _storeDir.mkdirs();
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
     */
    @Override
    public boolean isPassivating()
    {
        return true;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param os the output stream to save to
     * @param id identity of the session
     * @param data the info of the session
     * @throws IOException
     */
    private void save(OutputStream os, String id, SessionData data)  throws IOException
    {    
        DataOutputStream out = new DataOutputStream(os);
        out.writeUTF(id);
        out.writeUTF(_context.getCanonicalContextPath());
        out.writeUTF(_context.getVhost());
        out.writeUTF(data.getLastNode());
        out.writeLong(data.getCreated());
        out.writeLong(data.getAccessed());
        out.writeLong(data.getLastAccessed());
        out.writeLong(data.getCookieSet());
        out.writeLong(data.getExpiry());
        out.writeLong(data.getMaxInactiveMs());
        
        List<String> keys = new ArrayList<String>(data.getKeys());
        out.writeInt(keys.size());
        ObjectOutputStream oos = new ObjectOutputStream(out);
        for (String name:keys)
        {
            oos.writeUTF(name);
            oos.writeObject(data.getAttribute(name));
        }
    }

    /**
     * @param id identity of session
     * @return the filename of the session data store
     */
    private String getFileName (String id)
    {
        return _context.getCanonicalContextPath()+"_"+_context.getVhost()+"_"+id;
    }


    /**
     * @param is inputstream containing session data
     * @return the session data
     * @throws Exception
     */
    private SessionData load (InputStream is)
            throws Exception
    {
        String id = null;

        try
        {
            SessionData data = null;
            DataInputStream di = new DataInputStream(is);

            id = di.readUTF();
            String contextPath = di.readUTF();
            String vhost = di.readUTF();
            String lastNode = di.readUTF();
            long created = di.readLong();
            long accessed = di.readLong();
            long lastAccessed = di.readLong();
            long cookieSet = di.readLong();
            long expiry = di.readLong();
            long maxIdle = di.readLong();

            data = newSessionData(id, created, accessed, lastAccessed, maxIdle); 
            data.setContextPath(contextPath);
            data.setVhost(vhost);
            data.setLastNode(lastNode);
            data.setCookieSet(cookieSet);
            data.setExpiry(expiry);
            data.setMaxInactiveMs(maxIdle);

            // Attributes
            restoreAttributes(di, di.readInt(), data);

            return data;        
        }
        catch (Exception e)
        {
            throw new UnreadableSessionDataException(id, _context, e);
        }
    }

    /**
     * @param is inputstream containing session data
     * @param size number of attributes
     * @param data the data to restore to
     * @throws Exception
     */
    private void restoreAttributes (InputStream is, int size, SessionData data)
            throws Exception
    {
        if (size>0)
        {
            // input stream should not be closed here
            Map<String,Object> attributes = new HashMap<String,Object>();
            ClassLoadingObjectInputStream ois =  new ClassLoadingObjectInputStream(is);
            for (int i=0; i<size;i++)
            {
                String key = ois.readUTF();
                Object value = ois.readObject();
                attributes.put(key,value);
            }
            data.putAllAttributes(attributes);
        }
    }



}
