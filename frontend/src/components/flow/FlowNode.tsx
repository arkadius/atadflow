import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { useNodeTypes } from '@/hooks/useNodeTypes';
import type { FlowNodeData } from '@/types/flow';

function FlowNodeComponent({ data, selected }: NodeProps) {
  const { getNodeType } = useNodeTypes();
  const nodeData = data as unknown as FlowNodeData;
  const descriptor = getNodeType(nodeData.nodeType);

  return (
    <div className={`flow-node${selected ? ' selected' : ''}`}>
      {descriptor?.inputs.map((port) => (
        <Handle
          key={port.id}
          type="target"
          position={Position.Left}
          id={port.id}
          style={{ background: 'var(--color-primary)' }}
        />
      ))}
      <div className="flow-node-header">
        <span>{nodeData.label}</span>
        <span className="flow-node-type">{descriptor?.label ?? nodeData.nodeType}</span>
      </div>
      {descriptor && (
        <div className="flow-node-body">
          {Object.entries(descriptor.configSchema)
            .slice(0, 3)
            .map(([key, field]) => (
              <div key={key} className="flow-node-field">
                <label>{field.label}</label>
                <span style={{ fontSize: 11, color: 'var(--color-text)' }}>
                  {String(nodeData.config[key] ?? field.defaultValue ?? '')}
                </span>
              </div>
            ))}
        </div>
      )}
      {descriptor?.outputs.map((port) => (
        <Handle
          key={port.id}
          type="source"
          position={Position.Right}
          id={port.id}
          style={{ background: 'var(--color-primary)' }}
        />
      ))}
    </div>
  );
}

export const FlowNodeMemo = memo(FlowNodeComponent);
