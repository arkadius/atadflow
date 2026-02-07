import { useState } from 'react';
import type { Node } from '@xyflow/react';
import type { FlowNodeData } from '@/types/flow';
import type { NodeTypeDescriptor } from '@/types/node';
import { NodeConfigForm } from './NodeConfigForm';
import { CodeEditor } from './CodeEditor';

interface NodeSidePanelProps {
  node: Node<FlowNodeData>;
  descriptor: NodeTypeDescriptor | undefined;
  onUpdateData: (data: Partial<FlowNodeData>) => void;
  onClose: () => void;
}

export function NodeSidePanel({ node, descriptor, onUpdateData, onClose }: NodeSidePanelProps) {
  const [tab, setTab] = useState<'config' | 'code'>('config');
  const data = node.data;

  const handleConfigChange = (key: string, value: unknown) => {
    onUpdateData({ config: { ...data.config, [key]: value } });
  };

  const handleCodeChange = (code: string) => {
    onUpdateData({ pythonCode: code || null });
  };

  return (
    <div className="side-panel">
      <div className="side-panel-header">
        <h3>{data.label}</h3>
        <button onClick={onClose}>X</button>
      </div>
      <div className="side-panel-tabs">
        <button
          className={`side-panel-tab${tab === 'config' ? ' active' : ''}`}
          onClick={() => setTab('config')}
        >
          Config
        </button>
        <button
          className={`side-panel-tab${tab === 'code' ? ' active' : ''}`}
          onClick={() => setTab('code')}
        >
          Code
        </button>
      </div>
      <div className="side-panel-content">
        {tab === 'config' && descriptor && (
          <>
            <div className="config-field" style={{ marginBottom: 12 }}>
              <label>Label</label>
              <input
                value={data.label}
                onChange={(e) => onUpdateData({ label: e.target.value })}
              />
            </div>
            <NodeConfigForm
              configSchema={descriptor.configSchema}
              config={data.config}
              onChange={handleConfigChange}
            />
          </>
        )}
        {tab === 'code' && (
          <div style={{ height: 'calc(100vh - 240px)' }}>
            <CodeEditor
              value={data.pythonCode ?? ''}
              onChange={handleCodeChange}
            />
          </div>
        )}
      </div>
    </div>
  );
}
