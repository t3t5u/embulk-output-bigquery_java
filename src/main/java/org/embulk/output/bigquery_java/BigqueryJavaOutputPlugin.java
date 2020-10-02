package org.embulk.output.bigquery_java;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.Table;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.output.bigquery_java.config.BigqueryConfigValidator;
import org.embulk.output.bigquery_java.config.BigqueryTaskBuilder;
import org.embulk.output.bigquery_java.config.PluginTask;
import org.embulk.output.bigquery_java.exception.BigqueryException;
import org.embulk.spi.Exec;
import org.embulk.spi.OutputPlugin;
import org.embulk.spi.Schema;
import org.embulk.spi.TransactionalPageOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BigqueryJavaOutputPlugin
        implements OutputPlugin {
    private final Logger logger = LoggerFactory.getLogger(BigqueryJavaOutputPlugin.class);
    private List<Path> paths;
    private final ConcurrentHashMap<Long, BigqueryFileWriter> writers = BigqueryUtil.getFileWriters();

    @Override
    public ConfigDiff transaction(ConfigSource config,
                                  Schema schema, int taskCount,
                                  OutputPlugin.Control control) {
        PluginTask task = config.loadConfig(PluginTask.class);
        BigqueryConfigValidator.validate(task);
        BigqueryTaskBuilder.build(task);
        BigqueryClient client = new BigqueryClient(task, schema);
        autoCreate(task, client);

        control.run(task.dump());
        this.writers.values().forEach(BigqueryFileWriter::close);
        logger.info("embulk-output-bigquery: finish to create intermediate files");

        try {
            paths = BigqueryUtil.getIntermediateFiles(task);
        } catch (Exception e) {
            logger.info(e.getMessage());
            throw new RuntimeException(e);
        }
        if (paths.isEmpty()) {
            logger.info("embulk-output-bigquery: Nothing for transfer");
            client.createTableIfNotExist(task.getTable(), task.getDataset());
            // append, replace, delete_in_advance
            if (! "append_direct".equals(task.getMode())) {
                client.deleteTable(task.getTempTable().get());
            }

            return Exec.newConfigDiff();
        }

        logger.debug("embulk-output-bigquery: LOAD IN PARALLEL {}",
                paths.stream().map(Path::toString).collect(Collectors.joining("\n")));

        // transfer data to BQ from files
        ExecutorService executor = Executors.newFixedThreadPool(paths.size());
        List<Future<JobStatistics>> jobStatisticFutures = new ArrayList<>();
        List<JobStatistics.LoadStatistics> statistics = new ArrayList<>();

        for (Path path : paths) {
            Future<JobStatistics> jobStatisticsFuture = executor.submit(new BigqueryJobRunner(task, schema, path));
            jobStatisticFutures.add(jobStatisticsFuture);
        }

        for (Future<JobStatistics> jobStatisticFuture : jobStatisticFutures) {
            try {
                statistics.add((JobStatistics.LoadStatistics) jobStatisticFuture.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        BigqueryTransactionReport report = getTransactionReport(task, client, statistics, this.writers.values());
        if (task.getAbortOnError().get() && !task.getIsSkipJobResultCheck()) {
            if (report.getNumInputRows().compareTo(report.getNumOutputRows()) != 0) {
                String msg = String.format("ABORT: `num_input_rows (%d)` and `num_output_rows (%d)` does not match",
                        report.getNumInputRows(), report.getNumOutputRows());
                throw new RuntimeException(msg);
            }
        }

        if (task.getMode().equals("append") && task.getBeforeLoad().isPresent()) {
            logger.info("embulk-output-bigquery: before_load will be executed");
            logger.info("embulk-output-bigquery: {}", task.getBeforeLoad().get());
            client.executeQuery(task.getBeforeLoad().get());
        }

        if (task.getTempTable().isPresent()) {
            if (task.getMode().equals("append")) {
                client.copy(task.getTempTable().get(), task.getTable(), task.getDataset(), JobInfo.WriteDisposition.WRITE_APPEND);
            } else {
                client.copy(task.getTempTable().get(), task.getTable(), task.getDataset(), JobInfo.WriteDisposition.WRITE_TRUNCATE);
            }
            client.deleteTable(task.getTempTable().get());
        }

        if (task.getDeleteFromLocalWhenJobEnd()) {
            paths.forEach(p -> p.toFile().delete());
        } else {
            paths.forEach(p -> {
                File intermediateFile = new File(p.toString());
                if (intermediateFile.exists()) {
                    logger.info("embulk-output-bigquery: keep {}", p.toString());
                }
            });
        }

        return Exec.newConfigDiff();
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource,
                             Schema schema, int taskCount,
                             OutputPlugin.Control control) {
        throw new UnsupportedOperationException("bigquery_java output plugin does not support resuming");
    }

    @Override
    public void cleanup(TaskSource taskSource,
                        Schema schema, int taskCount,
                        List<TaskReport> successTaskReports) {
    }

    @Override
    public TransactionalPageOutput open(TaskSource taskSource, Schema schema, int taskIndex) {
        PluginTask task = taskSource.loadTask(PluginTask.class);
        return new BigqueryPageOutput(task, schema);
    }

    protected void autoCreate(PluginTask task, BigqueryClient client){
        if (task.getAutoCreateDataset()){
            client.createDataset(task.getDataset());
        }else{
            Dataset dataset = client.getDataset(task.getDataset());
            if (dataset == null){
                throw new BigqueryException(String.format("dataset %s is not found", task.getDataset()));
            }
        }

        if (task.getMode().equals("replace_backup") && !task.getOldDataset().get().equals(task.getDataset())){
            if (task.getAutoCreateDataset()){
                client.createDataset(task.getDataset());
            }else{
                Dataset dataset = client.getDataset(task.getOldDataset().get());
                if (dataset == null){
                    throw new BigqueryException(String.format("dataset %s is not found", task.getDataset()));
                }
            }
        }

        switch (task.getMode()){
            case "delete_in_advance":
                client.deleteTableOrPartition(task.getTable());
                client.createTableIfNotExist(task.getTempTable().get(), task.getDataset());
                break;
            case "replace":
                client.createTableIfNotExist(task.getTempTable().get(), task.getDataset());
                // TODO: create table to support partition
                break;
            case "append":
                client.createTableIfNotExist(task.getTempTable().get(), task.getDataset());
                // TODO: create table to support partition
                break;
            case "replace_backup":
                client.createTableIfNotExist(task.getTemplateTable().get());
                client.createTableIfNotExist(task.getTable());
                // needs for when a partition
                client.createTableIfNotExist(task.getOldTable().get(),task.getOldDataset().get());
                break;
            case "append_direct":
                if (task.getAutoCreateTable()) {
                    client.createTableIfNotExist(task.getTable(), task.getDataset());
                }else{
                    Table table = client.getTable(task.getTable());
                    if (table == null){
                        throw new BigqueryException(String.format("%s.%s not found, create table or enable auto_create_table", task.getDataset(), task.getTable()));
                    }
                }
                break;
            default:
                // never reach here
                throw new RuntimeException(String.format("mode %s is not supported", task.getMode()));
        }

    }


    protected BigqueryTransactionReport getTransactionReport(PluginTask task,
                                                             BigqueryClient client,
                                                             List<JobStatistics.LoadStatistics> statistics, Collection<BigqueryFileWriter> writers) {
        long inputRows = writers.stream().map(BigqueryFileWriter::getCount).reduce(0L, Long::sum);
        if (task.getIsSkipJobResultCheck()) {
            return new BigqueryTransactionReport(BigInteger.valueOf(inputRows));
        }

        long responseRows = statistics.stream().map(JobStatistics.LoadStatistics::getOutputRows).reduce(0L, Long::sum);
        BigInteger outputRows;
        if (task.getTempTable().isPresent()) {
            outputRows = client.getTable(task.getTempTable().get()).getNumRows();
        } else {
            outputRows = BigInteger.valueOf(responseRows);
        }
        BigInteger rejectedRows = BigInteger.valueOf(inputRows).subtract(outputRows);

        return new BigqueryTransactionReport(
                BigInteger.valueOf(inputRows),
                BigInteger.valueOf(responseRows),
                outputRows,
                rejectedRows);
    }
}
