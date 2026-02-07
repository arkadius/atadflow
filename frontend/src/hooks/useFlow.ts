import { useState, useEffect, useCallback } from 'react';
import {
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type OnConnect,
  addEdge,
  type Connection,
} from '@xyflow/react';
import { flowsApi } from '@/api/flows';
import {
  flowNodesToReactFlow,
  flowEdgesToReactFlow,
  reactFlowToFlowNodes,
  reactFlowToFlowEdges,
  type FlowNodeData,
} from '@/types/flow';

export function useFlow(flowId: string | undefined) {
  const [flowName, setFlowName] = useState('');
  const [flowDescription, setFlowDescription] = useState('');
  const [nodes, setNodes, onNodesChange] = useNodesState<Node<FlowNodeData>>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!flowId) return;
    flowsApi.get(flowId).then((flow) => {
      setFlowName(flow.name);
      setFlowDescription(flow.description);
      setNodes(flowNodesToReactFlow(flow.nodes));
      setEdges(flowEdgesToReactFlow(flow.edges));
      setLoading(false);
    });
  }, [flowId, setNodes, setEdges]);

  const onConnect: OnConnect = useCallback(
    (connection: Connection) => {
      setEdges((eds) => addEdge({ ...connection, type: 'dataEdge', animated: true }, eds));
    },
    [setEdges],
  );

  const save = useCallback(async () => {
    if (!flowId) return;
    setSaving(true);
    await flowsApi.update(flowId, {
      name: flowName,
      description: flowDescription,
      nodes: reactFlowToFlowNodes(nodes),
      edges: reactFlowToFlowEdges(edges),
    });
    setSaving(false);
  }, [flowId, flowName, flowDescription, nodes, edges]);

  const updateNodeData = useCallback(
    (nodeId: string, data: Partial<FlowNodeData>) => {
      setNodes((nds) =>
        nds.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, ...data } } : n)),
      );
    },
    [setNodes],
  );

  return {
    flowName,
    setFlowName,
    flowDescription,
    setFlowDescription,
    nodes,
    edges,
    setNodes,
    setEdges,
    onNodesChange,
    onEdgesChange,
    onConnect,
    save,
    saving,
    loading,
    updateNodeData,
  };
}
