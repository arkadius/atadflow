import type { Node, Edge } from '@xyflow/react';

export interface FlowNodeDto {
  id?: string;
  nodeKey: string;
  label: string;
  nodeType: string;
  positionX: number;
  positionY: number;
  config: Record<string, unknown>;
  pythonCode: string | null;
}

export interface FlowEdgeDto {
  id?: string;
  sourceNode: string;
  targetNode: string;
  sourceHandle: string | null;
  targetHandle: string | null;
}

export interface FlowDto {
  id: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
  nodes: FlowNodeDto[];
  edges: FlowEdgeDto[];
}

export interface FlowSummaryDto {
  id: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateFlowRequest {
  name: string;
  description: string;
}

export interface UpdateFlowRequest {
  name: string;
  description: string;
  nodes: FlowNodeDto[];
  edges: FlowEdgeDto[];
}

export interface FlowNodeData extends Record<string, unknown> {
  label: string;
  nodeType: string;
  config: Record<string, unknown>;
  pythonCode: string | null;
}

export function flowNodesToReactFlow(nodes: FlowNodeDto[]): Node<FlowNodeData>[] {
  return nodes.map((n) => ({
    id: n.nodeKey,
    type: 'flowNode',
    position: { x: n.positionX, y: n.positionY },
    data: {
      label: n.label,
      nodeType: n.nodeType,
      config: n.config ?? {},
      pythonCode: n.pythonCode,
    },
  }));
}

export function flowEdgesToReactFlow(edges: FlowEdgeDto[]): Edge[] {
  return edges.map((e, i) => ({
    id: `e-${e.sourceNode}-${e.targetNode}-${i}`,
    source: e.sourceNode,
    target: e.targetNode,
    sourceHandle: e.sourceHandle,
    targetHandle: e.targetHandle,
    type: 'dataEdge',
    animated: true,
  }));
}

export function reactFlowToFlowNodes(nodes: Node<FlowNodeData>[]): FlowNodeDto[] {
  return nodes.map((n) => ({
    nodeKey: n.id,
    label: n.data.label,
    nodeType: n.data.nodeType,
    positionX: n.position.x,
    positionY: n.position.y,
    config: n.data.config,
    pythonCode: n.data.pythonCode,
  }));
}

export function reactFlowToFlowEdges(edges: Edge[]): FlowEdgeDto[] {
  return edges.map((e) => ({
    sourceNode: e.source,
    targetNode: e.target,
    sourceHandle: e.sourceHandle ?? null,
    targetHandle: e.targetHandle ?? null,
  }));
}
