package io.atadflow.nodetype;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BuiltInNodeTypeProvider implements NodeTypeProvider {

    @Override
    public List<NodeTypeDescriptor> getNodeTypes() {
        return List.of(
                readStream(),
                filter(),
                select(),
                groupBy(),
                withWatermark(),
                writeStream()
        );
    }

    private NodeTypeDescriptor readStream() {
        Map<String, ConfigFieldDescriptor> config = new LinkedHashMap<>();
        config.put("format", new ConfigFieldDescriptor(
                "Format", "select", "rate",
                List.of("kafka", "file", "socket", "rate")));
        config.put("options", new ConfigFieldDescriptor(
                "Options", "key-value-list", null, null));
        return new NodeTypeDescriptor(
                "read-stream", "Read Stream",
                List.of(),
                List.of(new PortDescriptor("out", "Out")),
                config,
                """
                {{varName}} = spark.readStream \\
                    .format("{{config.format}}") \\
                    {{#config.options}}.option("{{key}}", "{{value}}") \\
                    {{/config.options}}.load()
                """);
    }

    private NodeTypeDescriptor filter() {
        Map<String, ConfigFieldDescriptor> config = new LinkedHashMap<>();
        config.put("condition", new ConfigFieldDescriptor(
                "Condition", "expression", "", null));
        return new NodeTypeDescriptor(
                "filter", "Filter",
                List.of(new PortDescriptor("in", "In")),
                List.of(new PortDescriptor("out", "Out")),
                config,
                "{{varName}} = {{inputVar}}.filter(\"{{config.condition}}\")");
    }

    private NodeTypeDescriptor select() {
        Map<String, ConfigFieldDescriptor> config = new LinkedHashMap<>();
        config.put("columns", new ConfigFieldDescriptor(
                "Columns", "expression", "*", null));
        return new NodeTypeDescriptor(
                "select", "Select",
                List.of(new PortDescriptor("in", "In")),
                List.of(new PortDescriptor("out", "Out")),
                config,
                "{{varName}} = {{inputVar}}.selectExpr({{config.columns}})");
    }

    private NodeTypeDescriptor groupBy() {
        Map<String, ConfigFieldDescriptor> config = new LinkedHashMap<>();
        config.put("timestamp-column", new ConfigFieldDescriptor(
                "Timestamp Column", "string", "timestamp", null));
        config.put("window-size", new ConfigFieldDescriptor(
                "Window Size", "string", "10 minutes", null));
        config.put("slide-interval", new ConfigFieldDescriptor(
                "Slide Interval", "string", "", null));
        config.put("aggregates", new ConfigFieldDescriptor(
                "Aggregates", "expression", "", null));
        return new NodeTypeDescriptor(
                "group-by", "Group By",
                List.of(new PortDescriptor("in", "In")),
                List.of(new PortDescriptor("out", "Out")),
                config,
                """
                {{varName}} = {{inputVar}} \\
                    .groupBy(window(col("{{config.timestamp-column}}"), "{{config.window-size}}"{{#config.slide-interval}}, "{{config.slide-interval}}"{{/config.slide-interval}})) \\
                    .agg({{config.aggregates}})
                """);
    }

    private NodeTypeDescriptor withWatermark() {
        Map<String, ConfigFieldDescriptor> config = new LinkedHashMap<>();
        config.put("event-time-column", new ConfigFieldDescriptor(
                "Event Time Column", "string", "timestamp", null));
        config.put("delay-threshold", new ConfigFieldDescriptor(
                "Delay Threshold", "string", "10 seconds", null));
        return new NodeTypeDescriptor(
                "with-watermark", "With Watermark",
                List.of(new PortDescriptor("in", "In")),
                List.of(new PortDescriptor("out", "Out")),
                config,
                "{{varName}} = {{inputVar}}.withWatermark(\"{{config.event-time-column}}\", \"{{config.delay-threshold}}\")");
    }

    private NodeTypeDescriptor writeStream() {
        Map<String, ConfigFieldDescriptor> config = new LinkedHashMap<>();
        config.put("format", new ConfigFieldDescriptor(
                "Format", "select", "console",
                List.of("console", "kafka", "file")));
        config.put("output-mode", new ConfigFieldDescriptor(
                "Output Mode", "select", "append",
                List.of("append", "complete", "update")));
        config.put("trigger", new ConfigFieldDescriptor(
                "Trigger", "string", "", null));
        config.put("checkpoint-location", new ConfigFieldDescriptor(
                "Checkpoint Location", "string", "", null));
        return new NodeTypeDescriptor(
                "write-stream", "Write Stream",
                List.of(new PortDescriptor("in", "In")),
                List.of(),
                config,
                """
                query_{{varName}} = {{inputVar}}.writeStream \\
                    .format("{{config.format}}") \\
                    .outputMode("{{config.output-mode}}") \\
                    {{#config.trigger}}.trigger(processingTime="{{config.trigger}}") \\
                    {{/config.trigger}}{{#config.checkpoint-location}}.option("checkpointLocation", "{{config.checkpoint-location}}") \\
                    {{/config.checkpoint-location}}.start()
                """);
    }
}
