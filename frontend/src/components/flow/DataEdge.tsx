import { BaseEdge, getSmoothStepPath, type EdgeProps } from '@xyflow/react';

export function DataEdge(props: EdgeProps) {
  const [edgePath] = getSmoothStepPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    targetX: props.targetX,
    targetY: props.targetY,
    sourcePosition: props.sourcePosition,
    targetPosition: props.targetPosition,
    borderRadius: 12,
  });

  return <BaseEdge path={edgePath} className="data-edge-path" />;
}
