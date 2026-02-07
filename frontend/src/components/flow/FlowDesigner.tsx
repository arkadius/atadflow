import { useCallback, useRef, type DragEvent } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  MiniMap,
  type Node,
  type Edge,
  type OnNodesChange,
  type OnEdgesChange,
  type OnConnect,
  useReactFlow,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { FlowNodeMemo } from './FlowNode';
import { DataEdge } from './DataEdge';
import type { FlowNodeData } from '@/types/flow';
import type { NodeTypeDescriptor } from '@/types/node';

const nodeTypes = { flowNode: FlowNodeMemo };
const edgeTypes = { dataEdge: DataEdge };

interface FlowDesignerProps {
  nodes: Node<FlowNodeData>[];
  edges: Edge[];
  onNodesChange: OnNodesChange<Node<FlowNodeData>>;
  onEdgesChange: OnEdgesChange<Edge>;
  onConnect: OnConnect;
  onNodeSelect: (nodeId: string | null) => void;
  setNodes: React.Dispatch<React.SetStateAction<Node<FlowNodeData>[]>>;
}

let nodeIdCounter = 0;

export function FlowDesigner({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onNodeSelect,
  setNodes,
}: FlowDesignerProps) {
  const reactFlowWrapper = useRef<HTMLDivElement>(null);
  const { screenToFlowPosition } = useReactFlow();

  const onDragOver = useCallback((event: DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event: DragEvent) => {
      event.preventDefault();
      const raw = event.dataTransfer.getData('application/atadflow-node-type');
      if (!raw) return;

      const nodeType: NodeTypeDescriptor = JSON.parse(raw);
      const position = screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });

      const id = `node_${++nodeIdCounter}_${Date.now()}`;
      const defaults: Record<string, unknown> = {};
      for (const [key, field] of Object.entries(nodeType.configSchema)) {
        if (field.defaultValue != null) defaults[key] = field.defaultValue;
      }

      const newNode: Node<FlowNodeData> = {
        id,
        type: 'flowNode',
        position,
        data: {
          label: nodeType.label,
          nodeType: nodeType.id,
          config: defaults,
          pythonCode: null,
        },
      };
      setNodes((nds) => [...nds, newNode]);
    },
    [screenToFlowPosition, setNodes],
  );

  const onSelectionChange = useCallback(
    ({ nodes: selectedNodes }: { nodes: Node[]; edges: Edge[] }) => {
      if (selectedNodes.length === 1) {
        onNodeSelect(selectedNodes[0].id);
      } else {
        onNodeSelect(null);
      }
    },
    [onNodeSelect],
  );

  return (
    <div className="flow-designer" ref={reactFlowWrapper}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onSelectionChange={onSelectionChange}
        onDragOver={onDragOver}
        onDrop={onDrop}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        fitView
        proOptions={{ hideAttribution: true }}
      >
        <Controls />
        <Background gap={20} size={1} />
        <MiniMap
          style={{ background: 'var(--color-surface)' }}
          maskColor="rgba(0,0,0,0.5)"
        />
      </ReactFlow>
    </div>
  );
}
