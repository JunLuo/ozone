/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.container.common.statemachine;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.hdds.HddsUtils;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.datanode.metadata.DatanodeCRLStore;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.CRLStatusReport;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.CommandStatusReportsProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReportsProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.NodeReportProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.PipelineReportsProto;
import org.apache.hadoop.hdds.security.x509.certificate.client.CertificateClient;
import org.apache.hadoop.hdds.upgrade.HDDSLayoutVersionManager;
import org.apache.hadoop.hdds.utils.LegacyHadoopConfigurationSource;
import org.apache.hadoop.ozone.HddsDatanodeStopService;
import org.apache.hadoop.ozone.container.common.DatanodeLayoutStorage;
import org.apache.hadoop.ozone.container.common.report.ReportManager;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.CloseContainerCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.ClosePipelineCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.CommandDispatcher;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.CreatePipelineCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.DeleteBlocksCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.DeleteContainerCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.FinalizeNewLayoutVersionCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.RefreshVolumeUsageCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.ReplicateContainerCommandHandler;
import org.apache.hadoop.ozone.container.common.statemachine.commandhandler.SetNodeOperationalStateCommandHandler;
import org.apache.hadoop.ozone.container.keyvalue.TarContainerPacker;
import org.apache.hadoop.ozone.container.ozoneimpl.OzoneContainer;
import org.apache.hadoop.ozone.container.replication.ContainerReplicator;
import org.apache.hadoop.ozone.container.replication.DownloadAndImportReplicator;
import org.apache.hadoop.ozone.container.replication.MeasuredReplicator;
import org.apache.hadoop.ozone.container.replication.ReplicationServer.ReplicationConfig;
import org.apache.hadoop.ozone.container.replication.ReplicationSupervisor;
import org.apache.hadoop.ozone.container.replication.ReplicationSupervisorMetrics;
import org.apache.hadoop.ozone.container.replication.SimpleContainerDownloader;
import org.apache.hadoop.ozone.container.upgrade.DataNodeUpgradeFinalizer;
import org.apache.hadoop.ozone.container.upgrade.VersionedDatanodeFeatures;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.hadoop.ozone.upgrade.UpgradeFinalizer;
import org.apache.hadoop.ozone.upgrade.UpgradeFinalizer.StatusAndMessages;
import org.apache.hadoop.util.JvmPauseMonitor;
import org.apache.hadoop.util.Time;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.ratis.util.ExitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State Machine Class.
 */
public class DatanodeStateMachine implements Closeable {
  @VisibleForTesting
  static final Logger LOG =
      LoggerFactory.getLogger(DatanodeStateMachine.class);
  private final ExecutorService executorService;
  private final ConfigurationSource conf;
  private final SCMConnectionManager connectionManager;
  private StateContext context;
  private final OzoneContainer container;
  private final DatanodeCRLStore dnCRLStore;
  private final DatanodeDetails datanodeDetails;
  private final CommandDispatcher commandDispatcher;
  private final ReportManager reportManager;
  private long commandsHandled;
  private final AtomicLong nextHB;
  private volatile Thread stateMachineThread = null;
  private Thread cmdProcessThread = null;
  private final ReplicationSupervisor supervisor;

  private JvmPauseMonitor jvmPauseMonitor;
  private CertificateClient dnCertClient;
  private final HddsDatanodeStopService hddsDatanodeStopService;

  private HDDSLayoutVersionManager layoutVersionManager;
  private DatanodeLayoutStorage layoutStorage;
  private DataNodeUpgradeFinalizer upgradeFinalizer;

  /**
   * Used to synchronize to the OzoneContainer object created in the
   * constructor in a non-thread-safe way - see HDDS-3116.
   */
  private final ReadWriteLock constructionLock = new ReentrantReadWriteLock();
  private final MeasuredReplicator replicatorMetrics;
  private final ReplicationSupervisorMetrics replicationSupervisorMetrics;

  /**
   * Constructs a datanode state machine.
   * @param datanodeDetails - DatanodeDetails used to identify a datanode
   * @param conf - Configuration.
   * @param certClient - Datanode Certificate client, required if security is
   *                     enabled
   */
  public DatanodeStateMachine(DatanodeDetails datanodeDetails,
                              ConfigurationSource conf,
                              CertificateClient certClient,
                              HddsDatanodeStopService hddsDatanodeStopService,
                              DatanodeCRLStore crlStore) throws IOException {
    DatanodeConfiguration dnConf =
        conf.getObject(DatanodeConfiguration.class);

    this.hddsDatanodeStopService = hddsDatanodeStopService;
    this.conf = conf;
    this.datanodeDetails = datanodeDetails;

    // Expected to be initialized already.
    layoutStorage = new DatanodeLayoutStorage(conf,
        datanodeDetails.getUuidString());

    layoutVersionManager = new HDDSLayoutVersionManager(
        layoutStorage.getLayoutVersion());
    upgradeFinalizer = new DataNodeUpgradeFinalizer(layoutVersionManager);
    VersionedDatanodeFeatures.initialize(layoutVersionManager);

    this.dnCRLStore = crlStore;
    executorService = Executors.newFixedThreadPool(
        getEndPointTaskThreadPoolSize(),
        new ThreadFactoryBuilder()
            .setNameFormat("Datanode State Machine Task Thread - %d").build());
    connectionManager = new SCMConnectionManager(conf);
    context = new StateContext(this.conf, DatanodeStates.getInitState(), this);
    // OzoneContainer instance is used in a non-thread safe way by the context
    // past to its constructor, so we much synchronize its access. See
    // HDDS-3116 for more details.
    constructionLock.writeLock().lock();
    try {
      container = new OzoneContainer(this.datanodeDetails,
          conf, context, certClient);
    } finally {
      constructionLock.writeLock().unlock();
    }
    dnCertClient = certClient;
    nextHB = new AtomicLong(Time.monotonicNow());

    ContainerReplicator replicator =
        new DownloadAndImportReplicator(container.getContainerSet(),
            container.getController(),
            new SimpleContainerDownloader(conf, dnCertClient),
            new TarContainerPacker());

    replicatorMetrics = new MeasuredReplicator(replicator);

    ReplicationConfig replicationConfig =
        conf.getObject(ReplicationConfig.class);
    supervisor =
        new ReplicationSupervisor(container.getContainerSet(), context,
            replicatorMetrics, replicationConfig);

    replicationSupervisorMetrics =
        ReplicationSupervisorMetrics.create(supervisor);


    // When we add new handlers just adding a new handler here should do the
    // trick.
    commandDispatcher = CommandDispatcher.newBuilder()
        .addHandler(new CloseContainerCommandHandler())
        .addHandler(new DeleteBlocksCommandHandler(container.getContainerSet(),
            conf, dnConf.getBlockDeleteThreads(),
            dnConf.getBlockDeleteQueueLimit()))
        .addHandler(new ReplicateContainerCommandHandler(conf, supervisor))
        .addHandler(new DeleteContainerCommandHandler(
            dnConf.getContainerDeleteThreads()))
        .addHandler(new ClosePipelineCommandHandler())
        .addHandler(new CreatePipelineCommandHandler(conf))
        .addHandler(new SetNodeOperationalStateCommandHandler(conf))
        .addHandler(new FinalizeNewLayoutVersionCommandHandler())
        .addHandler(new RefreshVolumeUsageCommandHandler())
        .setConnectionManager(connectionManager)
        .setContainer(container)
        .setContext(context)
        .build();

    reportManager = ReportManager.newBuilder(conf)
        .setStateContext(context)
        .addPublisherFor(NodeReportProto.class)
        .addPublisherFor(ContainerReportsProto.class)
        .addPublisherFor(CommandStatusReportsProto.class)
        .addPublisherFor(PipelineReportsProto.class)
        .addPublisherFor(CRLStatusReport.class)
        .build();
  }

  private int getEndPointTaskThreadPoolSize() {
    // TODO(runzhiwang): current only support one recon, if support multiple
    //  recon in future reconServerCount should be the real number of recon
    int reconServerCount = 1;
    int totalServerCount = reconServerCount;

    try {
      totalServerCount += HddsUtils.getSCMAddressForDatanodes(conf).size();
    } catch (Exception e) {
      LOG.error("Fail to get scm addresses", e);
    }

    LOG.info("Datanode State Machine Task Thread Pool size {}",
        totalServerCount);
    return totalServerCount;
  }

  /**
   *
   * Return DatanodeDetails if set, return null otherwise.
   *
   * @return DatanodeDetails
   */
  public DatanodeDetails getDatanodeDetails() {
    return datanodeDetails;
  }


  /**
   * Returns the Connection manager for this state machine.
   *
   * @return - SCMConnectionManager.
   */
  public SCMConnectionManager getConnectionManager() {
    return connectionManager;
  }

  public OzoneContainer getContainer() {
    // See HDDS-3116 to explain the need for this lock
    constructionLock.readLock().lock();
    try {
      return this.container;
    } finally {
      constructionLock.readLock().unlock();
    }
  }

  public DatanodeCRLStore getDnCRLStore() {
    return dnCRLStore;
  }

  /**
   * Runs the state machine at a fixed frequency.
   */
  private void startStateMachineThread() throws IOException {
    long now = 0;

    reportManager.init();
    initCommandHandlerThread(conf);

    upgradeFinalizer.runPrefinalizeStateActions(layoutStorage, this);

    // Start jvm monitor
    jvmPauseMonitor = new JvmPauseMonitor();
    jvmPauseMonitor
        .init(LegacyHadoopConfigurationSource.asHadoopConfiguration(conf));
    jvmPauseMonitor.start();

    while (context.getState() != DatanodeStates.SHUTDOWN) {
      try {
        LOG.debug("Executing cycle Number : {}", context.getExecutionCount());
        long heartbeatFrequency = context.getHeartbeatFrequency();
        nextHB.set(Time.monotonicNow() + heartbeatFrequency);
        context.execute(executorService, heartbeatFrequency,
            TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        // Someone has sent interrupt signal, this could be because
        // 1. Trigger heartbeat immediately
        // 2. Shutdown has be initiated.
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        LOG.error("Unable to finish the execution.", e);
      }

      now = Time.monotonicNow();
      if (now < nextHB.get()) {
        if (!Thread.interrupted()) {
          try {
            Thread.sleep(nextHB.get() - now);
          } catch (InterruptedException e) {
            //triggerHeartbeat is called during the sleep
            Thread.currentThread().interrupt();
          }
        }
      }
    }

    // If we have got some exception in stateMachine we set the state to
    // shutdown to stop the stateMachine thread. Along with this we should
    // also stop the datanode.
    if (context.getShutdownOnError()) {
      LOG.error("DatanodeStateMachine Shutdown due to an critical error");
      hddsDatanodeStopService.stopService();
    }
  }

  public void handleFatalVolumeFailures() {
    LOG.error("DatanodeStateMachine Shutdown due to too many bad volumes, "
        + "check " + DatanodeConfiguration.FAILED_DATA_VOLUMES_TOLERATED_KEY
        + " and "
        + DatanodeConfiguration.FAILED_METADATA_VOLUMES_TOLERATED_KEY);
    hddsDatanodeStopService.stopService();
  }

  /**
   * Gets the current context.
   *
   * @return StateContext
   */
  public StateContext getContext() {
    return context;
  }

  /**
   * Sets the current context.
   *
   * @param context - Context
   */
  public void setContext(StateContext context) {
    this.context = context;
  }

  /**
   * Closes this stream and releases any system resources associated with it. If
   * the stream is already closed then invoking this method has no effect.
   * <p>
   * <p> As noted in {@link AutoCloseable#close()}, cases where the close may
   * fail require careful attention. It is strongly advised to relinquish the
   * underlying resources and to internally <em>mark</em> the {@code Closeable}
   * as closed, prior to throwing the {@code IOException}.
   *
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void close() throws IOException {
    if (stateMachineThread != null) {
      stateMachineThread.interrupt();
    }
    if (cmdProcessThread != null) {
      cmdProcessThread.interrupt();
    }
    context.setState(DatanodeStates.getLastState());
    replicationSupervisorMetrics.unRegister();
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }

      if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
        LOG.error("Unable to shutdown state machine properly.");
      }
    } catch (InterruptedException e) {
      LOG.error("Error attempting to shutdown.", e);
      executorService.shutdownNow();
      Thread.currentThread().interrupt();
    }

    if (connectionManager != null) {
      connectionManager.close();
    }

    if (container != null) {
      container.stop();
    }

    if (jvmPauseMonitor != null) {
      jvmPauseMonitor.stop();
    }

    if (commandDispatcher != null) {
      commandDispatcher.stop();
    }
  }

  /**
   * States that a datanode  can be in. GetNextState will move this enum from
   * getInitState to getLastState.
   */
  public enum DatanodeStates {
    INIT(1),
    RUNNING(2),
    SHUTDOWN(3);
    private final int value;

    /**
     * Constructs states.
     *
     * @param value  Enum Value
     */
    DatanodeStates(int value) {
      this.value = value;
    }

    /**
     * Returns the first State.
     *
     * @return First State.
     */
    public static DatanodeStates getInitState() {
      return INIT;
    }

    /**
     * The last state of endpoint states.
     *
     * @return last state.
     */
    public static DatanodeStates getLastState() {
      return SHUTDOWN;
    }

    /**
     * returns the numeric value associated with the endPoint.
     *
     * @return int.
     */
    public int getValue() {
      return value;
    }

    /**
     * Returns the next logical state that endPoint should move to. This
     * function assumes the States are sequentially numbered.
     *
     * @return NextState.
     */
    public DatanodeStates getNextState() {
      if (this.value < getLastState().getValue()) {
        int stateValue = this.getValue() + 1;
        for (DatanodeStates iter : values()) {
          if (stateValue == iter.getValue()) {
            return iter;
          }
        }
      }
      return getLastState();
    }

    public boolean isTransitionAllowedTo(DatanodeStates newState) {
      return newState.getValue() > getValue();
    }
  }

  /**
   * Start datanode state machine as a single thread daemon.
   */
  public void startDaemon() {
    Runnable startStateMachineTask = () -> {
      try {
        LOG.info("Ozone container server started.");
        startStateMachineThread();
      } catch (Exception ex) {
        LOG.error("Unable to start the DatanodeState Machine", ex);
      }
    };
    stateMachineThread =  new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("Datanode State Machine Daemon Thread")
        .setUncaughtExceptionHandler((Thread t, Throwable ex) -> {
          String message = "Terminate Datanode, encounter uncaught exception"
              + " in Datanode State Machine Thread";
          ExitUtils.terminate(1, message, ex, LOG);
        })
        .build().newThread(startStateMachineTask);
    stateMachineThread.start();
  }

  /**
   * Calling this will immediately trigger a heartbeat to the SCMs.
   * This heartbeat will also include all the reports which are ready to
   * be sent by datanode.
   */
  public void triggerHeartbeat() {
    if (stateMachineThread != null && isDaemonStarted()) {
      stateMachineThread.interrupt();
    }
  }

  /**
   * Waits for DatanodeStateMachine to exit.
   *
   * @throws InterruptedException
   */
  public void join() throws InterruptedException {
    if (stateMachineThread != null) {
      stateMachineThread.join();
    }

    if (cmdProcessThread != null) {
      cmdProcessThread.join();
    }
  }

  /**
   * Stop the daemon thread of the datanode state machine.
   */
  public synchronized void stopDaemon() {
    try {
      try {
        replicatorMetrics.close();
      } catch (Exception e) {
        LOG.error("Couldn't stop replicator metrics", e);
      }
      supervisor.stop();
      context.setShutdownGracefully();
      context.setState(DatanodeStates.SHUTDOWN);
      reportManager.shutdown();
      this.close();
      LOG.info("Ozone container server stopped.");
    } catch (IOException e) {
      LOG.error("Stop ozone container server failed.", e);
    }
  }

  public boolean isDaemonStarted() {
    return this.getContext().getExecutionCount() > 0;
  }

  /**
   *
   * Check if the datanode state machine daemon is stopped.
   *
   * @return True if datanode state machine daemon is stopped
   * and false otherwise.
   */
  @VisibleForTesting
  public boolean isDaemonStopped() {
    return this.executorService.isShutdown()
        && this.getContext().getState() == DatanodeStates.SHUTDOWN;
  }

  /**
   * Create a command handler thread.
   *
   * @param config
   */
  private void initCommandHandlerThread(ConfigurationSource config) {

    /**
     * Task that periodically checks if we have any outstanding commands.
     * It is assumed that commands can be processed slowly and in order.
     * This assumption might change in future. Right now due to this assumption
     * we have single command  queue process thread.
     */
    Runnable processCommandQueue = () -> {
      long now;
      while (getContext().getState() != DatanodeStates.SHUTDOWN) {
        SCMCommand command = getContext().getNextCommand();
        if (command != null) {
          commandDispatcher.handle(command);
          commandsHandled++;
        } else {
          try {
            // Sleep till the next HB + 1 second.
            now = Time.monotonicNow();
            if (nextHB.get() > now) {
              Thread.sleep((nextHB.get() - now) + 1000L);
            }
          } catch (InterruptedException e) {
            // Ignore this exception.
            Thread.currentThread().interrupt();
          }
        }
      }
    };

    // We will have only one thread for command processing in a datanode.
    cmdProcessThread = getCommandHandlerThread(processCommandQueue);
    cmdProcessThread.start();
  }

  private Thread getCommandHandlerThread(Runnable processCommandQueue) {
    Thread handlerThread = new Thread(processCommandQueue);
    handlerThread.setDaemon(true);
    handlerThread.setName("Command processor thread");
    handlerThread.setUncaughtExceptionHandler((Thread t, Throwable e) -> {
      // Let us just restart this thread after logging a critical error.
      // if this thread is not running we cannot handle commands from SCM.
      LOG.error("Critical Error : Command processor thread encountered an " +
          "error. Thread: {}", t.toString(), e);
      getCommandHandlerThread(processCommandQueue).start();
    });
    return handlerThread;
  }

  /**
   * Returns the number of commands handled  by the datanode.
   * @return  count
   */
  @VisibleForTesting
  public long getCommandHandled() {
    return commandsHandled;
  }

  /**
   * returns the Command Dispatcher.
   * @return CommandDispatcher
   */
  @VisibleForTesting
  public CommandDispatcher getCommandDispatcher() {
    return commandDispatcher;
  }

  @VisibleForTesting
  public ReplicationSupervisor getSupervisor() {
    return supervisor;
  }

  @VisibleForTesting
  public HDDSLayoutVersionManager getLayoutVersionManager() {
    return layoutVersionManager;
  }

  @VisibleForTesting
  public DatanodeLayoutStorage getLayoutStorage() {
    return layoutStorage;
  }

  public StatusAndMessages finalizeUpgrade()
      throws IOException {
    return upgradeFinalizer.finalize(datanodeDetails.getUuidString(), this);
  }

  public StatusAndMessages queryUpgradeStatus()
      throws IOException {
    return upgradeFinalizer.reportStatus(datanodeDetails.getUuidString(),
        true);
  }
  public UpgradeFinalizer<DatanodeStateMachine> getUpgradeFinalizer() {
    return upgradeFinalizer;
  }
}
