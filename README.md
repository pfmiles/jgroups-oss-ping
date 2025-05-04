# jgroups-oss-ping

* 一个基于阿里云对象存储服务（OSS）的JGroups Ping协议实现。
* A jgroups ping protocol implementation based on Aliyun Object Storage Service (OSS).

# 使用说明

* 这是一个jgroups ping协议的实现，基于阿里云对象存储服务（OSS）, 将jar包引入到classpath中后，直接以
  `com.github.pfmiles.jgroups.aliyun.OSS_PING`标签方式配置在xml中，例如：

```xml

<com.github.pfmiles.jgroups.aliyun.OSS_PING endpoint="${oss_ping.endpoint:oss-cn-hangzhou.aliyuncs.com}"
                                            access_id="${oss_ping.access_id}"
                                            access_key="${oss_ping.access_key}"
                                            bucket_name="${oss_ping.bucket:replace_your_bucket_name_here}"
                                            bucket_prefix="${oss_ping.bucket_prefix:jgroups}"/>
```

* `endpoint`为OSS服务的访问地址，`access_id`和`access_key`为OSS的访问凭证，`bucket_name`为OSS的bucket名称，`bucket_prefix`
  为OSS的bucket前缀，默认为`jgroups`。
* OOS_PING一共就引入了这5个参数，更多的参数配置请参考FILE_PING，因为OSS_PING继承自FILE_PING。
* maven坐标：

```xml

<dependency>
    <groupId>com.github.pfmiles</groupId>
    <artifactId>jgroups-oss-ping</artifactId>
    <version>1.0.0</version>
</dependency>
```

* 下面以一个完整的使用案例为例，说明OSS_PING在jgroups中的使用方式：
    * jgroups.xml配置：
    ```xml
      <!--
      Based on tcp.xml but with new com.github.pfmiles.jgroups.aliyun.OSS_PING.
      -->
      <config xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
          xmlns="urn:org:jgroups" 
          xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">
  
          <TCP bind_addr="${jgroups.bind_addr:match-address:127.0.0.1}"
               bind_port="${jgroups.bind_port:7800}"
               recv_buf_size="${tcp.recv_buf_size:5M}"
               send_buf_size="${tcp.send_buf_size:5M}"
               max_bundle_size="64K"
               sock_conn_timeout="5000ms"
               thread_pool.enabled="true"
               thread_pool.min_threads="1"
               thread_pool.max_threads="8"
               thread_pool.keep_alive_time="60000"/>
      
          <com.github.pfmiles.jgroups.aliyun.OSS_PING endpoint="${oss_ping.endpoint:oss-cn-hangzhou.aliyuncs.com}"
                                                      access_id="${oss_ping.access_id}"
                                                      access_key="${oss_ping.access_key}"
                                                      bucket_name="${oss_ping.bucket:gamble-hz}"
                                                      bucket_prefix="${oss_ping.bucket_prefix:jgroups}"/>
      
          <MERGE3 min_interval="10000"
                  max_interval="30000"/>
      
          <FD_SOCK/>
          <FD_ALL3 timeout="40000" interval="5000"/>
          <VERIFY_SUSPECT timeout="1500"/>
          <pbcast.NAKACK2 use_mcast_xmit="false"
                          discard_delivered_msgs="true" xmit_interval="100"/>
      
          <UNICAST3 xmit_interval="100"/>
          <pbcast.STABLE stability_delay="1000" desired_avg_gossip="50000"
                         max_bytes="4M"/>
          <pbcast.GMS print_local_addr="true" join_timeout="3000" max_join_attempts="5" view_bundling="true"/>
          <UFC max_credits="2000000"
               min_threshold="0.4"/>
          <MFC max_credits="2M"
               min_threshold="0.4"/>
          <FRAG3 frag_size="60K"/>
          <pbcast.STATE_TRANSFER/>
      </config>
    ```
    * 加载该xml文件并启动集群中一个节点的kotlin代码示例, 该示例只是简单地让集群中的每一个节点随时能知晓集群中有多少个成员：
    ```kotlin
    package test

    import org.jgroups.JChannel
    import org.jgroups.Message
    import org.jgroups.ReceiverAdapter
    import org.jgroups.View
    import org.jgroups.stack.IpAddress
    import java.util.concurrent.CopyOnWriteArraySet
    
    /**
    * 集群感知
    *
    * @author pf-miles
    */
    object ClusterAware {
      private lateinit var channel: JChannel
      val members = CopyOnWriteArraySet<String>()
  
      init {
        if (MainArgs.clusterAware) {
          initCluster()
          Runtime.getRuntime().addShutdownHook(Thread { close() })
        }
      }
  
      private fun initCluster() {
        channel = JChannel("jgroups.xml")
        channel.setReceiver(object : ReceiverAdapter() {
          override fun receive(msg: Message) { 
            super.receive(msg)
          }
  
          override fun viewAccepted(newView: View) { 
            val newMembers = newView.members.filterIsInstance<IpAddress>().map { it.ipAddress.hostAddress }.toSet()
            members.retainAll(newMembers)
            members.addAll(newMembers)
          }
        })
        channel.connect(MainArgs.clusterName)
      }
  
      private fun close() {
        if (::channel.isInitialized && channel.isOpen) {
          channel.close()
        }
      }
    }
    ```
    * main函数略
    * 由jgroups.xml配置可知，需要从命令行中传入的系统属性有:`oss_ping.access_id`和`oss_ping.access_key`，那么在启动java进程时传入
      `-D`系统属性即可，例如：
    ```bash
    java -Doss_ping.access_id=your_access_id_here -Doss_ping.access_key=your_access_key_here -jar target/xxx.jar
    ```
    * 亦即：jgroups.xml中定义的变量由java进程启动时的系统属性来提供，当然你也可以在java程序中通过`System.setProperty`
      来设置这些变量。

-----

# Usage Instructions

* This is a JGroups PING protocol implementation based on Alibaba Cloud Object Storage Service (OSS). After adding the
  JAR to your classpath, configure it in XML using the `com.github.pfmiles.jgroups.aliyun.OSS_PING` tag. Example:

```xml

<com.github.pfmiles.jgroups.aliyun.OSS_PING endpoint="${oss_ping.endpoint:oss-cn-hangzhou.aliyuncs.com}"
                                            access_id="${oss_ping.access_id}"
                                            access_key="${oss_ping.access_key}"
                                            bucket_name="${oss_ping.bucket:replace_your_bucket_name_here}"
                                            bucket_prefix="${oss_ping.bucket_prefix:jgroups}"/>
```

* Attributes explanation:
    * endpoint: OSS service endpoint (default: oss-cn-hangzhou.aliyuncs.com)
    * access_id: OSS access credentials ID
    * access_key: OSS access credentials key
    * bucket_name: Target OSS bucket name
    * bucket_prefix: Bucket prefix for JGroups metadata storage (default: jgroups)
    * OOS_PING introduces these 5 parameters in total. For more parameter configurations, please refer to FILE_PING, as
      OSS_PING inherits from FILE_PING.
* Maven Coordinates:

```xml

<dependency>
    <groupId>com.github.pfmiles</groupId>
    <artifactId>jgroups-oss-ping</artifactId>
    <version>1.0.0</version>
</dependency>
```

* Complete Configuration Example:
    * JGroups XML configuration:
  ```xml
    <!--
    Based on tcp.xml but with new com.github.pfmiles.jgroups.aliyun.OSS_PING.
    -->
    <config xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" 
        xmlns="urn:org:jgroups" 
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">

        <TCP bind_addr="${jgroups.bind_addr:match-address:127.0.0.1}"
             bind_port="${jgroups.bind_port:7800}"
             recv_buf_size="${tcp.recv_buf_size:5M}"
             send_buf_size="${tcp.send_buf_size:5M}"
             max_bundle_size="64K"
             sock_conn_timeout="5000ms"
             thread_pool.enabled="true"
             thread_pool.min_threads="1"
             thread_pool.max_threads="8"
             thread_pool.keep_alive_time="60000"/>
    
        <com.github.pfmiles.jgroups.aliyun.OSS_PING endpoint="${oss_ping.endpoint:oss-cn-hangzhou.aliyuncs.com}"
                                                    access_id="${oss_ping.access_id}"
                                                    access_key="${oss_ping.access_key}"
                                                    bucket_name="${oss_ping.bucket:gamble-hz}"
                                                    bucket_prefix="${oss_ping.bucket_prefix:jgroups}"/>
    
        <MERGE3 min_interval="10000"
                max_interval="30000"/>
    
        <FD_SOCK/>
        <FD_ALL3 timeout="40000" interval="5000"/>
        <VERIFY_SUSPECT timeout="1500"/>
        <pbcast.NAKACK2 use_mcast_xmit="false"
                        discard_delivered_msgs="true" xmit_interval="100"/>
    
        <UNICAST3 xmit_interval="100"/>
        <pbcast.STABLE stability_delay="1000" desired_avg_gossip="50000"
                       max_bytes="4M"/>
        <pbcast.GMS print_local_addr="true" join_timeout="3000" max_join_attempts="5" view_bundling="true"/>
        <UFC max_credits="2000000"
             min_threshold="0.4"/>
        <MFC max_credits="2M"
             min_threshold="0.4"/>
        <FRAG3 frag_size="60K"/>
        <pbcast.STATE_TRANSFER/>
    </config>
  ```
    * Kotlin code example for loading the XML file and starting a cluster node (demonstrates real-time cluster member
      awareness):
  ```kotlin
  package test

  import org.jgroups.JChannel
  import org.jgroups.Message
  import org.jgroups.ReceiverAdapter
  import org.jgroups.View
  import org.jgroups.stack.IpAddress
  import java.util.concurrent.CopyOnWriteArraySet
  
  /**
  * 集群感知
  *
  * @author pf-miles
  */
  object ClusterAware {
    private lateinit var channel: JChannel
    val members = CopyOnWriteArraySet<String>()

    init {
      if (MainArgs.clusterAware) {
        initCluster()
        Runtime.getRuntime().addShutdownHook(Thread { close() })
      }
    }

    private fun initCluster() {
      channel = JChannel("jgroups.xml")
      channel.setReceiver(object : ReceiverAdapter() {
        override fun receive(msg: Message) { 
          super.receive(msg)
        }

        override fun viewAccepted(newView: View) { 
          val newMembers = newView.members.filterIsInstance<IpAddress>().map { it.ipAddress.hostAddress }.toSet()
          members.retainAll(newMembers)
          members.addAll(newMembers)
        }
      })
      channel.connect(MainArgs.clusterName)
    }

    private fun close() {
      if (::channel.isInitialized && channel.isOpen) {
        channel.close()
      }
    }
  }
  ```
    * main function omitted
    * The variables defined in jgroups.xml are provided by system properties passed to the Java process. For example:
  ```bash
  java -Doss_ping.access_id=your_access_id_here -Doss_ping.access_key=your_access_key_here -jar target/xxx.jar
  ```
    * Alternatively, you can set these variables programmatically using `System.setProperty` in Java program.
