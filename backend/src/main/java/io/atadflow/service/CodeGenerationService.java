package io.atadflow.service;

import io.atadflow.dto.FlowDto;
import io.atadflow.dto.FlowEdgeDto;
import io.atadflow.dto.FlowNodeDto;
import io.atadflow.nodetype.NodeTypeDescriptor;
import io.atadflow.nodetype.NodeTypeRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

@ApplicationScoped
public class CodeGenerationService {

    @Inject
    NodeTypeRegistry nodeTypeRegistry;

    @ConfigProperty(name = "spark.connect.url")
    String sparkConnectUrl;

    public String generateCode(FlowDto flow) {
        List<FlowNodeDto> sorted = topologicalSort(flow.nodes(), flow.edges());

        // Build a map from nodeKey to variable name
        Map<String, String> varNames = new HashMap<>();
        Map<String, Integer> typeCounts = new HashMap<>();
        for (FlowNodeDto node : sorted) {
            String base = node.nodeType().replace("-", "_");
            int count = typeCounts.merge(base, 1, Integer::sum);
            varNames.put(node.nodeKey(), "df_" + base + "_" + count);
        }

        // Build a map from nodeKey to its input variable (from incoming edge)
        Map<String, String> inputVars = new HashMap<>();
        for (FlowEdgeDto edge : flow.edges()) {
            inputVars.put(edge.targetNode(), varNames.get(edge.sourceNode()));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("from pyspark.sql import SparkSession\n");
        sb.append("from pyspark.sql.functions import *\n");
        sb.append("import signal\n");
        sb.append("import sys\n\n");
        sb.append("spark = SparkSession.builder.remote(\"").append(sparkConnectUrl).append("\").getOrCreate()\n\n");
        sb.append("def handle_shutdown(sig, frame):\n");
        sb.append("    print(f\"Received signal {sig}, stopping Spark session...\")\n");
        sb.append("    try:\n");
        sb.append("        spark.stop()\n");
        sb.append("    except Exception as e:\n");
        sb.append("        print(f\"Error stopping Spark session: {e}\")\n");
        sb.append("    sys.exit(0)\n\n");
        sb.append("signal.signal(signal.SIGTERM, handle_shutdown)\n");
        sb.append("signal.signal(signal.SIGINT, handle_shutdown)\n\n");

        for (FlowNodeDto node : sorted) {
            String varName = varNames.get(node.nodeKey());
            String inputVar = inputVars.getOrDefault(node.nodeKey(), "");

            if (node.pythonCode() != null && !node.pythonCode().isBlank()) {
                sb.append("# ").append(node.label()).append("\n");
                sb.append(node.pythonCode().strip()).append("\n\n");
            } else {
                Optional<NodeTypeDescriptor> descriptor = nodeTypeRegistry.get(node.nodeType());
                if (descriptor.isPresent()) {
                    String code = resolveTemplate(descriptor.get().defaultCodeTemplate(), varName, inputVar, node.config());
                    sb.append("# ").append(node.label()).append("\n");
                    sb.append(code.strip()).append("\n");

                    sb.append("\n");
                }
            }
        }

        sb.append("try:\n");
        sb.append("    spark.streams.awaitAnyTermination()\n");
        sb.append("except KeyboardInterrupt:\n");
        sb.append("    handle_shutdown(None, None)\n");
        return sb.toString();
    }

    private String resolveTemplate(String template, String varName, String inputVar, Map<String, Object> config) {
        String result = template;
        result = result.replace("{{varName}}", varName);
        result = result.replace("{{inputVar}}", inputVar);
        if (config != null) {
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                result = result.replace("{{config." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
        }
        // Remove unreplaced simple placeholders
        result = result.replaceAll("\\{\\{config\\.[^}]+}}", "");
        // Remove mustache-style conditional blocks for missing values
        result = result.replaceAll("(?s)\\{\\{#[^}]+}}.*?\\{\\{/[^}]+}}", "");
        return result;
    }

    private List<FlowNodeDto> topologicalSort(List<FlowNodeDto> nodes, List<FlowEdgeDto> edges) {
        Map<String, FlowNodeDto> nodeMap = new LinkedHashMap<>();
        for (FlowNodeDto n : nodes) nodeMap.put(n.nodeKey(), n);

        Map<String, Set<String>> inEdges = new HashMap<>();
        Map<String, Set<String>> outEdges = new HashMap<>();
        for (FlowNodeDto n : nodes) {
            inEdges.put(n.nodeKey(), new HashSet<>());
            outEdges.put(n.nodeKey(), new HashSet<>());
        }
        for (FlowEdgeDto e : edges) {
            inEdges.get(e.targetNode()).add(e.sourceNode());
            outEdges.get(e.sourceNode()).add(e.targetNode());
        }

        List<FlowNodeDto> sorted = new ArrayList<>();
        Queue<String> queue = new ArrayDeque<>();
        for (var entry : inEdges.entrySet()) {
            if (entry.getValue().isEmpty()) queue.add(entry.getKey());
        }

        while (!queue.isEmpty()) {
            String key = queue.poll();
            sorted.add(nodeMap.get(key));
            for (String target : outEdges.get(key)) {
                inEdges.get(target).remove(key);
                if (inEdges.get(target).isEmpty()) queue.add(target);
            }
        }

        return sorted;
    }
}
