import { useState, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import { ReactFlowProvider } from '@xyflow/react';
import { useFlow } from '@/hooks/useFlow';
import { useNodeTypes } from '@/hooks/useNodeTypes';
import { useJobs } from '@/hooks/useJobs';
import { flowsApi } from '@/api/flows';
import { FlowDesigner } from '@/components/flow/FlowDesigner';
import { NodePalette } from '@/components/flow/NodePalette';
import { NodeSidePanel } from '@/components/editor/NodeSidePanel';
import { CodeEditor } from '@/components/editor/CodeEditor';
import { JobPanel } from '@/components/jobs/JobPanel';

function FlowEditorInner() {
  const { flowId } = useParams<{ flowId: string }>();
  const flow = useFlow(flowId);
  const { nodeTypes, getNodeType } = useNodeTypes();
  const { jobs, submit, cancel } = useJobs(flowId);

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [view, setView] = useState<'canvas' | 'code'>('canvas');
  const [generatedCode, setGeneratedCode] = useState('');

  const selectedNode = flow.nodes.find((n) => n.id === selectedNodeId) ?? null;

  const handleNodeSelect = useCallback((nodeId: string | null) => {
    setSelectedNodeId(nodeId);
  }, []);

  const handleViewCode = useCallback(async () => {
    if (!flowId) return;
    if (view === 'canvas') {
      const code = await flowsApi.generateCode(flowId);
      setGeneratedCode(code);
      setView('code');
    } else {
      setView('canvas');
    }
  }, [flowId, view]);

  if (flow.loading) return <div style={{ padding: 24 }}>Loading...</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, overflow: 'hidden' }}>
      {/* Toolbar */}
      <div
        style={{
          height: 40,
          borderBottom: '1px solid var(--color-border)',
          display: 'flex',
          alignItems: 'center',
          padding: '0 12px',
          gap: 8,
          flexShrink: 0,
        }}
      >
        <input
          value={flow.flowName}
          onChange={(e) => flow.setFlowName(e.target.value)}
          style={{ fontWeight: 600, maxWidth: 250, background: 'transparent', border: 'none' }}
        />
        <div style={{ flex: 1 }} />
        <div className="view-toggle">
          <button className={view === 'canvas' ? 'active' : ''} onClick={() => setView('canvas')}>
            Canvas
          </button>
          <button className={view === 'code' ? 'active' : ''} onClick={handleViewCode}>
            Code
          </button>
        </div>
        <button className="primary" onClick={flow.save} disabled={flow.saving}>
          {flow.saving ? 'Saving...' : 'Save'}
        </button>
      </div>

      {/* Main content */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {view === 'canvas' ? (
          <>
            <NodePalette nodeTypes={nodeTypes} />
            <FlowDesigner
              nodes={flow.nodes}
              edges={flow.edges}
              onNodesChange={flow.onNodesChange}
              onEdgesChange={flow.onEdgesChange}
              onConnect={flow.onConnect}
              onNodeSelect={handleNodeSelect}
              setNodes={flow.setNodes}
            />
            {selectedNode && (
              <NodeSidePanel
                node={selectedNode}
                descriptor={getNodeType(selectedNode.data.nodeType)}
                onUpdateData={(data) => flow.updateNodeData(selectedNode.id, data)}
                onClose={() => setSelectedNodeId(null)}
              />
            )}
          </>
        ) : (
          <div style={{ flex: 1 }}>
            <CodeEditor value={generatedCode} onChange={() => {}} readOnly />
          </div>
        )}
      </div>

      {/* Job panel */}
      <JobPanel jobs={jobs} onSubmit={submit} onCancel={cancel} />
    </div>
  );
}

export function FlowEditorPage() {
  return (
    <ReactFlowProvider>
      <FlowEditorInner />
    </ReactFlowProvider>
  );
}
