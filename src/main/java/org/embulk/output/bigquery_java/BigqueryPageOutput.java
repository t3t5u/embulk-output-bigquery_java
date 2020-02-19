package org.embulk.output.bigquery_java;

import org.embulk.config.TaskReport;
import org.embulk.output.bigquery_java.config.PluginTask;
import org.embulk.output.bigquery_java.visitor.JsonColumnVisitor;
import org.embulk.output.bigquery_java.visitor.BigqueryColumnVisitor;
import org.embulk.spi.*;

import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// TODO: add function to count write row
// this object has to create one per thread
// ref: https://amiyasahu.github.io/singleton-class-per-thread-in-java.html
//
// AtomicInteger#incrementAndGet()
// https://qiita.com/shinido/items/b8e69091da5ddb4bd9ad#atomicintegerincrementandget%E3%82%92%E4%BD%BF%E3%81%86
public class BigqueryPageOutput implements TransactionalPageOutput {
    private final Logger logger = LoggerFactory.getLogger(BigqueryPageOutput.class);
    private PageReader pageReader;
    private final Schema schema;
    private PluginTask task;
    private OutputStream os;
    private HashMap<Long, BigqueryFileWriter> writers;

    public BigqueryPageOutput(PluginTask task, Schema schema, HashMap<Long, BigqueryFileWriter> writers) {
        this.task = task;
        this.schema = schema;
        this.pageReader = new PageReader(schema);
        this.writers = writers;
    }

    @Override
    public void add(Page page) {
        pageReader.setPage(page);
        BigqueryThreadLocalFileWriter.setFileWriter(this.task);
        BigqueryFileWriter writer = new BigqueryFileWriter(this.task);
        try {
            this.os = writer.outputStream();
            while (pageReader.nextRecord()) {
                BigqueryColumnVisitor visitor = new JsonColumnVisitor(this.task,
                        pageReader, this.task.getColumnOptions().orElse(Collections.emptyList()));
                pageReader.getSchema().getColumns().forEach(col-> col.visit(visitor));
                BigqueryThreadLocalFileWriter.write(visitor.getByteArray());
            }
        } catch (Exception e) {
            logger.info(e.getMessage());
        }
    }

    @Override
    public void finish()
    {
        close();
    }

    @Override
    public void close()
    {
        if (pageReader != null) {
            pageReader.close();
            pageReader = null;
        }
        writers.put(Thread.currentThread().getId(), BigqueryThreadLocalFileWriter.getFileWriter());
    }

    @Override
    public void abort() {
    }

    @Override
    public TaskReport commit()
    {
        return Exec.newTaskReport();
    }
}
