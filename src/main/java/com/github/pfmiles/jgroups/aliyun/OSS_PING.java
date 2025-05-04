package com.github.pfmiles.jgroups.aliyun;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.internal.OSSHeaders;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.StorageClass;
import org.jgroups.Address;
import org.jgroups.annotations.Property;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.FILE_PING;
import org.jgroups.protocols.PingData;
import org.jgroups.util.Responses;
import org.jgroups.util.Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * <p>
 * A JGroups protocol that uses Aliyun OSS as a backing store for cluster membership information.
 * <p>
 * This protocol is based on the {@link FILE_PING} protocol, but uses Aliyun OSS as a backing store instead of the local file system.
 *
 * @author pf-miles
 */
public class OSS_PING extends FILE_PING {
    protected static final short JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER = 1077;
    protected static final int SERIALIZATION_BUFFER_SIZE = 4096;
    protected static final String SERIALIZED_CONTENT_TYPE = "text/plain";
    protected static final String MAGIC_NUMBER_SYSTEM_PROPERTY = "ossping.magic_number";
    private static final long CLEANER_INTERVAL = 60000;

    @Property(description = "The Aliyun OSS endpoint with which to communicate (required).", systemProperty = {"jgroups.aliyun.oss.endpoint", "JGROUPS_ALIYUN_OSS_ENDPOINT"}, writable = false)
    protected String endpoint;
    @Property(description = "The Aliyun OSS access id with which to communicate (required).", systemProperty = {"jgroups.aliyun.oss.access_id", "JGROUPS_ALIYUN_OSS_ACCESS_ID"}, writable = false)
    protected String access_id;
    @Property(description = "The Aliyun OSS access key with which to communicate (required).", systemProperty = {"jgroups.aliyun.oss.access_key", "JGROUPS_ALIYUN_OSS_ACCESS_KEY"}, writable = false)
    protected String access_key;

    @Property(description = "The Aliyun OSS bucket name to use (required).", systemProperty = {"jgroups.aliyun.oss.bucket_name", "JGROUPS_ALIYUN_OSS_BUCKET_NAME"}, writable = false)
    protected String bucket_name;

    @Property(description = "The prefix to prefix all Aliyun OSS paths with, e.g. 'jgroups/' (required).", systemProperty = {"jgroups.aliyun.oss.bucket_prefix", "JGROUPS_ALIYUN_OSS_BUCKET_PREFIX"}, writable = false)
    protected String bucket_prefix;

    protected OSS ossClient;

    protected Thread nodeFileCleaner;

    static {
        short magicNumber = JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER;
        String sysMagicNumberProp = System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY);
        if (sysMagicNumberProp != null && !sysMagicNumberProp.trim().isEmpty()) {
            try {
                magicNumber = Short.parseShort(System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY));
            } catch (NumberFormatException e) {
                LogFactory.getLog(OSS_PING.class).warn("Could not convert provided property '%s' to short. Using default magic number: %d.", System.getProperty(MAGIC_NUMBER_SYSTEM_PROPERTY), JGROUPS_PROTOCOL_DEFAULT_MAGIC_NUMBER);
            }
        }
        ClassConfigurator.addProtocol(magicNumber, OSS_PING.class);
    }

    @Override
    public void init() throws Exception {
        super.init();

        if (bucket_prefix == null || bucket_prefix.equals("/")) {
            bucket_prefix = "";
        } else if (!bucket_prefix.endsWith("/") && !bucket_prefix.isEmpty()) {
            bucket_prefix = bucket_prefix + "/";
        }

        OSSClientBuilder builder = new OSSClientBuilder();
        ossClient = builder.build(endpoint, access_id, access_key, new ClientBuilderConfiguration());
        log.info("Using Aliyun OSS ping at endpoint '%s' with bucket '%s' and prefix '%s'.", endpoint, bucket_name, bucket_prefix);

        // establish stale node file cleaner
        nodeFileCleaner = new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    if (is_coord) {
                        final String clusterPrefix = getClusterPrefix(cluster_name);
                        final ObjectListing objects = doList(clusterPrefix, null, null);
                        for (final OSSObjectSummary ossObject : objects.getObjectSummaries()) {
                            if (ossObject.getSize() > 0) {
                                if (ossObject.getLastModified().getTime() < System.currentTimeMillis() - CLEANER_INTERVAL) {
                                    GetObjectRequest getObjectRequest = new GetObjectRequest(bucket_name, ossObject.getKey());
                                    OSSObject objectCtt = ossClient.getObject(getObjectRequest);

                                    final List<PingData> data = this.read(objectCtt.getObjectContent());
                                    if (data == null || data.isEmpty()) {
                                        quietDelOssFile(ossObject.getKey());
                                        continue;
                                    }

                                    // delete if no ping data of current node
                                    if (data.stream().noneMatch(pingData -> pingData.isCoord() && pingData.getAddress().equals(local_addr))) {
                                        quietDelOssFile(objectCtt.getKey());
                                        log.info("Stale node file '%s' deleted.", objectCtt.getKey());
                                    }
                                }
                            } else {
                                quietDelOssFile(ossObject.getKey());
                            }
                        }
                    }
                    Thread.sleep(CLEANER_INTERVAL);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    log.error("Error occurred in stale node file cleaner, ignored.", t);
                }
            }
            log.info("Stale node file cleaner stopped.");
        }, "oss-ping-stale-node-file-cleaner");
        nodeFileCleaner.start();
        log.info("Stale node file cleaner started.");
    }

    @Override
    public void stop() {
        super.stop();
        this.nodeFileCleaner.interrupt();
    }

    @Override
    protected void createRootDir() {
        // ignore, bucket has to exist
    }

    protected String getClusterPrefix(final String clusterName) {
        return bucket_prefix + clusterName + "/";
    }

    @Override
    protected void readAll(final List<Address> members, final String clustername, final Responses responses) {
        if (clustername == null) return;

        final String clusterPrefix = getClusterPrefix(clustername);

        if (log.isTraceEnabled()) log.trace("Getting entries for cluster '%s'.", clusterPrefix);

        try {
            final ObjectListing objects = doList(clusterPrefix, null, null);

            if (log.isTraceEnabled())
                log.trace("Got object listing, %d entries for cluster '%s'.", objects.getObjectSummaries().size(), clusterPrefix);


            for (final OSSObjectSummary ossObject : objects.getObjectSummaries()) {
                if (log.isTraceEnabled()) log.trace("Fetching data for object '%s'.", ossObject.getKey());

                if (ossObject.getSize() > 0) {
                    GetObjectRequest getObjectRequest = new GetObjectRequest(bucket_name, ossObject.getKey());
                    OSSObject objectCtt = ossClient.getObject(getObjectRequest);

                    if (log.isTraceEnabled()) {
                        log.trace("Parsing data for object '%s': '%s'.", ossObject.getKey(), objectCtt.toString());
                    }

                    final List<PingData> data = this.read(objectCtt.getObjectContent());
                    if (data == null) {
                        log.debug("Fetched update for cluster '%s' member list in Aliyun OSS is empty.", clusterPrefix);
                        break;
                    }
                    for (final PingData pingData : data) {
                        if (members == null || members.contains(pingData.getAddress())) {
                            responses.addResponse(pingData, pingData.isCoord());
                            if (log.isTraceEnabled())
                                log.trace("Added member '%s', members '%s'.", pingData, members != null);
                        }
                        if (local_addr != null && !local_addr.equals(pingData.getAddress())) {
                            addDiscoveryResponseToCaches(pingData.getAddress(), pingData.getLogicalName(), pingData.getPhysicalAddr());
                            if (log.isTraceEnabled()) {
                                log.trace("Added possible member '%s' with local address '%s'.", pingData, local_addr);
                            }
                        }
                        if (log.isTraceEnabled())
                            log.trace("Processed entry in Aliyun OSS: '%s' -> '%s'.", ossObject.getKey(), pingData);
                    }
                } else {
                    if (log.isTraceEnabled()) log.trace("Skipping empty object '%s'.", ossObject.getKey());
                }
            }
            log.debug("Fetched update for member list in Aliyun OSS for cluster '%s'.", clusterPrefix);
        } catch (final Exception e) {
            log.error(String.format("Failed getting member list from Aliyun OSS for cluster '%s'.", clusterPrefix), e);
        }
    }

    @Override
    protected void write(final List<PingData> list, final String clustername) {
        final String filename = addressToFilename(local_addr);
        final String key = getClusterPrefix(clustername) + filename;

        try {
            final ByteArrayOutputStream outStream = new ByteArrayOutputStream(SERIALIZATION_BUFFER_SIZE);
            this.write(list, outStream);
            final byte[] data = outStream.toByteArray();

            if (log.isTraceEnabled()) {
                log.trace("New Aliyun OSS file content (%d bytes): %s", data.length, new String(data));
            }

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(SERIALIZED_CONTENT_TYPE);
            metadata.setHeader(OSSHeaders.OSS_STORAGE_CLASS, StorageClass.Standard.name());
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucket_name, key, new ByteArrayInputStream(data), metadata);

            ossClient.putObject(putObjectRequest);

            log.debug("Wrote member list to Aliyun OSS: '%s' -> '%s', bucket: %s", key, list, bucket_name);
        } catch (final Exception e) {
            log.error(String.format("Failed to update member list in Aliyun OSS in '%s'.", key), e);
        }
    }

    @Override
    protected void remove(final String clustername, final Address addr) {
        if (clustername == null || addr == null) return;
        String filename = addressToFilename(addr);
        String key = getClusterPrefix(clustername) + filename;
        try {
            quietDelOssFile(key);
            if (log.isTraceEnabled()) log.trace("Removing key '%s'.", key);
        } catch (Exception e) {
            log.error(Util.getMessage("FailureRemovingData"), e);
        }
    }

    @Override
    protected void removeAll(String clustername) {
        if (clustername == null) return;

        final String clusterPrefix = getClusterPrefix(clustername);

        try {
            final ObjectListing objects = doList(clusterPrefix, null, null);

            if (log.isTraceEnabled())
                log.trace("Got object listing, '%d' entries for cluster '%s'.", objects.getObjectSummaries().size(), clusterPrefix);

            for (final OSSObjectSummary object : objects.getObjectSummaries()) {
                if (log.isTraceEnabled()) log.trace("Fetching data for object '%s'.", object.getKey());
                try {
                    quietDelOssFile(object.getKey());
                    if (log.isTraceEnabled()) log.trace("Removing '%s'.", object.getKey());
                } catch (Throwable t) {
                    log.error("Failed deleting object '%s': %s", object.getKey(), t);
                }
            }
        } catch (Exception ex) {
            log.error(Util.getMessage("FailedDeletingAllObjects"), ex);
        }
    }

    private void quietDelOssFile(String ossKey) {
        try {
            ossClient.deleteObjects(new DeleteObjectsRequest(bucket_name).withQuiet(true).withKeys(Collections.singletonList(ossKey)));
        } catch (Exception e) {
            log.error(String.format("Failed deleting object '%s'", ossKey), e);
        }
    }

    // batch listing
    private ObjectListing doList(String path, String delimiter, Predicate<OSSObjectSummary> filter) {
        final int maxKeys = 200;
        String nextMarker = null;
        ObjectListing list;

        List<OSSObjectSummary> all = new ArrayList<>();
        do {
            list = ossClient.listObjects(new ListObjectsRequest(bucket_name).withPrefix(path).withDelimiter(delimiter).withMarker(nextMarker).withMaxKeys(maxKeys));

            if (filter != null) {
                for (OSSObjectSummary x : list.getObjectSummaries()) {
                    if (filter.test(x)) {
                        all.add(x);
                    }
                }
            } else {
                all.addAll(list.getObjectSummaries());
            }

            nextMarker = list.getNextMarker();
        } while (list.isTruncated());

        list.setObjectSummaries(all);

        return list;
    }

}
