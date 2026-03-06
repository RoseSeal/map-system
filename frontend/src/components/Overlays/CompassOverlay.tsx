/**
 * Compass Overlay Component
 * Displays heading indicator and OZT sectors
 */

import { useMemo } from 'react';
import { useRiskStore, selectOwnShip, selectTargets } from '../../store';
import { COLORS } from '../../config';

const COMPASS_SIZE = 180;
const CENTER = COMPASS_SIZE / 2;
const RADIUS = 70;

export function CompassOverlay() {
  const ownShip = useRiskStore(selectOwnShip);
  const targets = useRiskStore(selectTargets);
  
  // Get active OZT sectors
  const oztSectors = useMemo(() => {
    return targets
      .filter(t => 
        t.risk_assessment.ozt_sector?.is_active &&
        (t.risk_assessment.risk_level === 'WARNING' || t.risk_assessment.risk_level === 'ALARM')
      )
      .map(t => ({
        id: t.id,
        start: t.risk_assessment.ozt_sector!.start_angle_deg,
        end: t.risk_assessment.ozt_sector!.end_angle_deg,
        level: t.risk_assessment.risk_level,
      }));
  }, [targets]);
  
  if (!ownShip) return null;
  
  const heading = ownShip.dynamics.hdg;
  
  return (
    <div className="bg-gray-900/80 backdrop-blur-sm rounded-full p-2">
      <svg 
        width={COMPASS_SIZE} 
        height={COMPASS_SIZE}
        className="drop-shadow-lg"
      >
        {/* Background circle */}
        <circle
          cx={CENTER}
          cy={CENTER}
          r={RADIUS + 10}
          fill="rgba(17, 24, 39, 0.8)"
          stroke="rgba(75, 85, 99, 0.5)"
          strokeWidth={1}
        />
        
        {/* OZT Sectors */}
        {oztSectors.map(sector => (
          <OZTSectorArc
            key={sector.id}
            startAngle={sector.start}
            endAngle={sector.end}
            level={sector.level}
            heading={heading}
          />
        ))}
        
        {/* Compass ring */}
        <circle
          cx={CENTER}
          cy={CENTER}
          r={RADIUS}
          fill="none"
          stroke="rgba(156, 163, 175, 0.3)"
          strokeWidth={2}
        />
        
        {/* Cardinal directions */}
        {['N', 'E', 'S', 'W'].map((dir, i) => {
          const angle = i * 90 - heading;
          const rad = (angle - 90) * (Math.PI / 180);
          const x = CENTER + (RADIUS + 20) * Math.cos(rad);
          const y = CENTER + (RADIUS + 20) * Math.sin(rad);
          
          return (
            <text
              key={dir}
              x={x}
              y={y}
              fill={dir === 'N' ? COLORS.ALARM : '#9CA3AF'}
              fontSize={dir === 'N' ? 14 : 12}
              fontWeight={dir === 'N' ? 'bold' : 'normal'}
              textAnchor="middle"
              dominantBaseline="middle"
            >
              {dir}
            </text>
          );
        })}
        
        {/* Tick marks */}
        {Array.from({ length: 36 }).map((_, i) => {
          const angle = i * 10 - heading;
          const rad = (angle - 90) * (Math.PI / 180);
          const isMajor = i % 3 === 0;
          const innerR = RADIUS - (isMajor ? 10 : 5);
          
          return (
            <line
              key={i}
              x1={CENTER + innerR * Math.cos(rad)}
              y1={CENTER + innerR * Math.sin(rad)}
              x2={CENTER + RADIUS * Math.cos(rad)}
              y2={CENTER + RADIUS * Math.sin(rad)}
              stroke={isMajor ? '#9CA3AF' : '#4B5563'}
              strokeWidth={isMajor ? 2 : 1}
            />
          );
        })}
        
        {/* Ship indicator (always points up) */}
        <polygon
          points={`${CENTER},${CENTER - 25} ${CENTER - 8},${CENTER + 10} ${CENTER + 8},${CENTER + 10}`}
          fill={COLORS.SAFE}
          stroke="white"
          strokeWidth={1}
        />
        
        {/* Center circle */}
        <circle
          cx={CENTER}
          cy={CENTER}
          r={5}
          fill="#1F2937"
          stroke={COLORS.SAFE}
          strokeWidth={2}
        />
        
        {/* Heading text */}
        <text
          x={CENTER}
          y={CENTER + 45}
          fill="white"
          fontSize={14}
          fontWeight="bold"
          textAnchor="middle"
          fontFamily="monospace"
        >
          {heading.toFixed(1)}°
        </text>
      </svg>
    </div>
  );
}

interface OZTSectorArcProps {
  startAngle: number;
  endAngle: number;
  level: string;
  heading: number;
}

function OZTSectorArc({ startAngle, endAngle, level, heading }: OZTSectorArcProps) {
  // Adjust angles relative to ship heading
  const adjustedStart = startAngle - heading;
  const adjustedEnd = endAngle - heading;
  
  const startRad = (adjustedStart - 90) * (Math.PI / 180);
  const endRad = (adjustedEnd - 90) * (Math.PI / 180);
  
  const innerR = 20;
  const outerR = RADIUS - 5;
  
  // Calculate arc points
  const x1 = CENTER + outerR * Math.cos(startRad);
  const y1 = CENTER + outerR * Math.sin(startRad);
  const x2 = CENTER + outerR * Math.cos(endRad);
  const y2 = CENTER + outerR * Math.sin(endRad);
  const x3 = CENTER + innerR * Math.cos(endRad);
  const y3 = CENTER + innerR * Math.sin(endRad);
  const x4 = CENTER + innerR * Math.cos(startRad);
  const y4 = CENTER + innerR * Math.sin(startRad);
  
  const largeArc = Math.abs(endAngle - startAngle) > 180 ? 1 : 0;
  
  const path = `
    M ${x1} ${y1}
    A ${outerR} ${outerR} 0 ${largeArc} 1 ${x2} ${y2}
    L ${x3} ${y3}
    A ${innerR} ${innerR} 0 ${largeArc} 0 ${x4} ${y4}
    Z
  `;
  
  const color = level === 'ALARM' ? COLORS.ALARM : COLORS.WARNING;
  
  return (
    <path
      d={path}
      fill={color}
      fillOpacity={0.4}
      stroke={color}
      strokeWidth={1}
    />
  );
}
