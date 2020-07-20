import * as React from 'react';
import { Block } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import { BlockInfo } from 'casperlabs-grpc/io/casperlabs/casper/consensus/info_pb';
import { ListInline, Loading, RefreshButton, shortHash } from './Utils';
import * as d3 from 'd3';
import { encodeBase16 } from 'casperlabs-sdk';
import { ToggleButton, ToggleStore } from './ToggleButton';
import { autorun } from 'mobx';
import * as d3xyzoom from 'd3-xyzoom';
import { observer } from 'mobx-react';

// https://bl.ocks.org/mapio/53fed7d84cd1812d6a6639ed7aa83868

const CircleRadius = 12;
const LineColor = '#AAA';
const FinalizedLineColor = '#83f2a1';
const OrphanedLineColor = '#FF0000';

export interface Props {
  title: string;
  refresh?: () => void;
  subscribeToggleStore?: ToggleStore;
  hideBallotsToggleStore?: ToggleStore;
  hideBlockHashToggleStore?: ToggleStore;
  blocks: BlockInfo[] | null;
  emptyMessage?: any;
  footerMessage?: any;
  width: string | number;
  height: string | number;
  selected?: BlockInfo;
  depth: number;
  onDepthChange?: (depth: number) => void;
  onSelected?: (block: BlockInfo) => void;
}

@observer
export class BlockDAG extends React.Component<Props, {}> {
  svg: SVGSVGElement | null = null;
  hint: HTMLDivElement | null = null;
  xTrans: d3.ScaleLinear<number, number> | null = null;
  yTrans: d3.ScaleLinear<number, number> | null = null;
  initialized = false;

  constructor(props: Props) {
    super(props);
    autorun(() => {
      this.renderGraph();
    }, {
      delay: 400
    })
  }

  render() {
    return (
      <div className="card mb-3">
        <div className="card-header">
          <span>{this.props.title}</span>
          <div className="float-right">
            <ListInline>
              {this.props.hideBallotsToggleStore && (
                <ToggleButton
                  label="Hide Ballots"
                  title="Hide Ballots"
                  toggleStore={this.props.hideBallotsToggleStore}
                  size="sm"
                />
              )}
              {this.props.subscribeToggleStore && (
                <ToggleButton
                  title="Subscribe to the latest added blocks"
                  label="Live Feed"
                  toggleStore={this.props.subscribeToggleStore}
                  size="sm"
                />
              )}
              {this.props.hideBlockHashToggleStore && (
                <ToggleButton
                  title="Hide BlockHash Label"
                  label="Hide Label"
                  toggleStore={this.props.hideBlockHashToggleStore}
                  size="sm"
                />
              )}
              {this.props.onDepthChange && (
                <select
                  title="Depth"
                  value={this.props.depth.toString()}
                  onChange={e =>
                    this.props.onDepthChange!(Number(e.target.value))
                  }
                >
                  {[10, 20, 50, 100].map(x => (
                    <option key={x} value={x}>
                      {x}
                    </option>
                  ))}
                </select>
              )}
              {this.props.refresh && (
                <RefreshButton refresh={() => this.props.refresh!()} />
              )}
            </ListInline>
          </div>
        </div>
        <div className="card-body">
          {this.props.blocks == null ? (
            <Loading />
          ) : this.props.blocks.length === 0 ? (
            <div className="small text-muted">
              {this.props.emptyMessage || 'No blocks to show.'}
            </div>
          ) : (
            <div className="svg-container">
              <svg
                width={this.props.width}
                height={this.props.height}
                ref={(ref: SVGSVGElement) => (this.svg = ref)}
              ></svg>
              <div
                className="svg-hint"
                ref={(ref: HTMLDivElement) => (this.hint = ref)}
              ></div>
            </div>
          )}
        </div>
        {this.props.footerMessage && (
          <div className="card-footer small text-muted">
            {this.props.footerMessage}
          </div>
        )}
      </div>
    );
  }

  /** Called so that the SVG is added when the component has been rendered,
   * however data will most likely still be uninitialized. */
  componentDidMount() {
    this.renderGraph();
  }

  renderGraph() {
    const hideBallot = this.props.hideBallotsToggleStore?.isPressed;
    const hideBlockHash = this.props.hideBlockHashToggleStore?.isPressed;

    let blocks = this.props.blocks;
    if (blocks == null || blocks.length === 0) {
      // The renderer will have removed the svg.
      this.initialized = false;
      return;
    }

    const svg = d3.select(this.svg);
    const hint = d3.select(this.hint);
    const validatorColor = consistentColor(d3.schemePaired);
    const eraColor = consistentColor(d3.schemeCategory10);
    // See what the actual width and height is.
    const width = $(this.svg!).width()!;
    const height = $(this.svg!).height()!;

    // see https://www.d3-graph-gallery.com/graph/interactivity_zoom.html#axisZoom
    // using axis transform function to simplify transform
    let initXTrans: d3.ScaleLinear<number, number> = d3
      .scaleLinear()
      .domain([0, width])
      .range([0, width]);
    let initYTrans: d3.ScaleLinear<number, number> = d3
      .scaleLinear()
      .domain([0, height])
      .range([0, height]);

    // Append items that will not change.
    if (!this.initialized) {
      this.xTrans = initXTrans;
      this.yTrans = initYTrans;
      // Add the zoomable container.
      svg.append('g').attr('class', 'container');

      // whether it is under horizontal only zoom mode
      let isHorizontal = false;
      const zoom = d3xyzoom
        .xyzoom()
        .extent([
          [0, 0],
          [width, height]
        ])
        .scaleExtent([
          [0.4, 4],
          [0.4, 4]
        ])
        .on('start', () => {
          // The first wheel event emits a start event; an end event is emitted when no wheel events are received for 150ms.
          // So if user press ctrlKey when fire first wheel event,
          // it enters the horizontal only zoom mode.
          if(d3.event.sourceEvent && d3.event.sourceEvent.type === 'wheel'){
            isHorizontal = d3.event.sourceEvent.ctrlKey;
            if(isHorizontal){
              svg.attr('cursor', 'ew-resize');
            }else{
              svg.attr('cursor', 'nesw-resize');
            }
          }else if(d3.event.sourceEvent && d3.event.sourceEvent.type === "mousedown"){
            svg.attr('cursor', 'grab');
          }
        })
        .on('zoom', () => {
          let t = d3.event.transform;
          this.xTrans = t.rescaleX(initXTrans);
          if (!isHorizontal) {
            this.yTrans = t.rescaleY(initYTrans);
          }
          updatePositions();
        })
        .on('end', () => {
          // reset
          isHorizontal = false;
          // set null to remove the attribute
          svg.attr('cursor', null);
          // see https://github.com/d3/d3-zoom/issues/48
          // Stored the current axis of x and y, and set the transform to be the identity transform,
          // where kx = 1, ky = 1, tx = ty = 0, so when switching between zoom mode,
          // both of them won't interleave.
          initXTrans = this.xTrans ?? initXTrans;
          initYTrans = this.yTrans ?? initYTrans;
          svg.property('__zoom', d3xyzoom.xyzoomIdentity);
        });

      svg.call(zoom);

      // add the defs of arrow which can be used to draw an arrow at the end of the lines to point at parents later
      svg
        .append('svg:defs')
        .append('svg:marker')
        .attr('id', 'arrow')
        .attr('refX', 6)
        .attr('refY', 6)
        .attr('markerWidth', 10)
        .attr('markerHeight', 10)
        .attr('orient', 'auto')
        .append('path')
        .attr('d', 'M 3 4 7 6 3 8')
        .attr('fill', LineColor);

      this.initialized = true;
    }

    // The container are the root layer to draw all node/label/line components
    const container = svg.select('.container');

    // Clear previous contents.
    container.selectAll('g').remove();

    let graph: Graph = calculateCoordinates(toGraph(blocks), width, height);

    const selectedId = this.props.selected && blockHash(this.props.selected);

    const link = container
      .append('g')
      .attr('class', 'links')
      .selectAll('line')
      .data(graph.links.filter((d:d3Link) => {
        if (hideBallot) {
          return isBlock(d.source.block) && isBlock(d.target.block);
        } else {
          return true;
        }
      }))
      .enter()
      .append('line')
      .attr('stroke', (d: d3Link) =>
        d.isOrphaned ? OrphanedLineColor : (d.isFinalized ? FinalizedLineColor : LineColor)
      )
      .attr('stroke-width', (d: d3Link) => (d.isMainParent ? 3 : 1))
      .attr('marker-end', 'url(#arrow)') // use the Arrow created above
      .attr('stroke-dasharray', (d: d3Link) =>
        d.isJustification ? '3, 3' : null
      )
      .attr('opacity', (d: d3Link) => (d.isJustification ? 0 : 1));

    const node = container
      .append('g')
      .attr('class', 'nodes')
      .selectAll('g')
      .data(
        graph.nodes.filter(node => {
          if (hideBallot) {
            return isBlock(node.block);
          } else {
            return true;
          }
        })
      )
      .enter()
      .append('g');

    node
      .append('circle')
      .attr('class', 'node')
      .attr('r', (d: d3Node) =>
        CircleRadius * (isBallot(d.block) ? 0.5 : 1.0))
      .attr('stroke', (d: d3Node) =>
        selectedId && d.id === selectedId ? '#E00' : eraColor(d.eraId)
      )
      .attr('stroke-width', (d: d3Node) =>
        selectedId && d.id === selectedId ? '7px' : '4px'
      )
      .attr('fill', (d: d3Node) => validatorColor(d.validator));

    // Append a node-label to each node
    const label = node
      .append('text')
      .attr('class', 'node-label')
      .text((d: d3Node) => d.title)
      .style('font-family', 'Arial')
      .style('font-size', 12)
      .style('pointer-events', 'none') // to prevent mouseover/drag capture
      .style('text-anchor', 'start')
      .attr('display', hideBlockHash ? 'none' : 'block');

    const focus = (d: any) => {
      let datum = d3.select(d3.event.target).datum() as d3Node;
      node.style('opacity', x =>
        graph.areNeighbours(x.id, datum.id) ? 1 : 0.1
      );
      label.attr('display', x =>
        graph.areNeighbours(x.id, datum.id) ? 'block' : 'none'
      );
      link.style('opacity', x =>
        x.source.id === datum.id || x.target.id === datum.id
          ? 1
          : x.isJustification
            ? 0
            : 0.1
      );
      hint.html(
        `Block: ${datum.id} @ ${datum.rank} <br /> Validator: ${datum.validator}`
      );
      hint.style('display', 'block');
    };

    const unfocus = () => {
      label.attr('display', hideBlockHash ? 'none' : 'block');
      node.style('opacity', 1);
      link.style('opacity', x => (x.isJustification ? 0 : 1));
      hint.style('display', 'none');
    };

    const select = (d: any) => {
      let datum = d3.select(d3.event.target).datum() as d3Node;
      this.props.onSelected && this.props.onSelected(datum.block);
    };

    node.on('mouseover', focus).on('mouseout', unfocus);
    node.on('click', select);

    const updatePositions = () => {
      const x = this.xTrans ?? initXTrans;
      const y = this.yTrans ?? initYTrans;
      // update position of node
      container
        .selectAll('circle.node')
        .attr('cx', (d: any) => x(d.x))
        .attr('cy', (d: any) => y(d.y));

      // update position of label
      container
        .selectAll('text.node-label')
        .attr('x', (d: any) => x(d.x) + 5)
        .attr('y', (d: any) => y(d.y) + 25)
        .attr('transform', (d: any) => `rotate(25 ${x(d.x)} ${y(d.y)})`); // rotate so a chain doesn't overlap on a small screen.

      // update positions of line
      container
        .selectAll('line')
        .attr('x1', (d: any) => x(d.source.x))
        .attr('y1', (d: any) => y(d.source.y))
        .attr('x2', (d: any) => {
          // We want the radius of Node keep the same after scaling, so we need use invert function to calculate that before scaling.
          const newRInX = x.invert(CircleRadius + 2) - x.invert(0);
          return x(
            d.source.x + (d.target.x - d.source.x) * shorten(d, newRInX)
          );
        })
        .attr('y2', (d: any) => {
          // We want the radius of Node keep the same after scaling, so we need use invert function to calculate that before scaling.
          const newRInY = y.invert(CircleRadius + 2) - y.invert(0);
          return y(
            d.source.y + (d.target.y - d.source.y) * shorten(d, newRInY)
          );
        });
    };

    updatePositions();
  }
}

interface d3Node {
  id: string;
  title: string;
  validator: string;
  eraId: string;
  rank: number;
  x?: number;
  y?: number;
  block: BlockInfo;
}

interface d3Link {
  source: d3Node;
  target: d3Node;
  isMainParent: boolean;
  isJustification: boolean;
  isFinalized: boolean;
  isOrphaned: boolean;
}

class Graph {
  private targets: Map<String, Set<String>> = new Map();

  constructor(public nodes: d3Node[], public links: d3Link[]) {
    links.forEach(link => {
      let targets = this.targets.get(link.source.id) || new Set<String>();
      targets.add(link.target.id);
      this.targets.set(link.source.id, targets);
    });
  }

  hasTarget = (from: string, to: string) =>
    this.targets.has(from) && this.targets.get(from)!.has(to);

  areNeighbours = (a: string, b: string) =>
    a === b || this.hasTarget(a, b) || this.hasTarget(b, a);
}

/** Turn blocks into the reduced graph structure. */
const toGraph = (blocks: BlockInfo[]) => {
  let nodes: d3Node[] = blocks.map(block => {
    let id = blockHash(block);
    return {
      id: id,
      title: shortHash(id),
      validator: validatorHash(block),
      eraId: keyBlockHash(block),
      rank: block
        .getSummary()!
        .getHeader()!
        .getJRank(),
      block: block
    };
  });

  let nodeMap = new Map(nodes.map(x => [x.id, x]));

  let links = blocks.flatMap(block => {
    let child = blockHash(block);

    let isChildFinalized = isFinalized(block);
    let isChildOrphaned = isOrphaned(block);
    let isChildBallot = isBallot(block);

    let parents = block
      .getSummary()!
      .getHeader()!
      .getParentHashesList_asU8()
      .map(h => encodeBase16(h));

    let parentSet = new Set(parents);

    let justifications = block
      .getSummary()!
      .getHeader()!
      .getJustificationsList()
      .map(x => encodeBase16(x.getLatestBlockHash_asU8()));

    let source = nodeMap.get(child)!;

    let parentLinks = parents
      .filter(p => nodeMap.has(p))
      .map(p => {
        let target = nodeMap.get(p)!;
        return {
          source: source,
          target: target,
          isMainParent: p === parents[0],
          isJustification: false,
          isFinalized: (isChildFinalized || isChildBallot) && isFinalized(target.block),
          // if child is an orphaned block, the link should be highlighted with OrphanedLineColor
          isOrphaned: isChildOrphaned
        };
      });

    let justificationLinks = justifications
      .filter(x => !parentSet.has(x))
      .filter(j => nodeMap.has(j))
      .map(j => {
        let target = nodeMap.get(j)!;
        return {
          source: source,
          target: target,
          isMainParent: false,
          isJustification: true,
          isFinalized: (isChildFinalized || isChildBallot) && isFinalized(target.block),
          isOrphaned: false
        };
      });

    return parentLinks.concat(justificationLinks);
  });

  return new Graph(nodes, links);
};

/** Calculate coordinates so that valiators are in horizontal swimlanes, time flowing left to right. */
const calculateCoordinates = (graph: Graph, width: number, height: number) => {
  const validators = [...new Set(graph.nodes.map(x => x.validator))].sort();
  const marginPercent = 0.4; // so that there are space between swimlanes
  const verticalStep = height / validators.length;
  const maxRank = Math.max(...graph.nodes.map(x => x.rank));
  const minRank = Math.min(...graph.nodes.map(x => x.rank));
  const horizontalStep = width / (maxRank - minRank + 2);

  // count how many nodes having the same (validator, rank)
  let countOfRanks = new Map<string, number>();
  // current index, for the specified key, curIndexOfRanks[key] = [0, countOfRanks[key])
  let curIndexOfRanks = new Map<string, number>();

  // since JavaScript doesn't support tuple as key of Map, we need to encode the primary keys
  let key = (node: d3Node) => `${node.validator},${node.rank}`;

  graph.nodes.forEach(node => {
    let k = key(node);
    if (countOfRanks.has(k)) {
      countOfRanks.set(k, countOfRanks.get(k)! + 1);
    } else {
      countOfRanks.set(k, 1);
    }
  });

  graph.nodes.forEach(node => {
    let k = key(node);
    let count = countOfRanks.get(k)!;
    let step = 0;

    if (count !== 1) {
      let index = curIndexOfRanks.has(k) ? curIndexOfRanks.get(k)! : 0;
      // for each node i of nodes NS having the same (validator, rank), c is the size of NS
      // its distance from the baseline of swimlane is (i / (c - 1) - 0.5) * height of swimlane
      // the height of swimlane is (1 - marginPercent) * verticalStep
      step = (index / (count - 1) - 0.5) * (1 - marginPercent);
      curIndexOfRanks.set(k, index + 1);
    }

    // (validators.indexOf(node.validator) + 0.5) * verticalStep is the y of the baseline of swimlane of node.validator
    node.y = (validators.indexOf(node.validator) + 0.5 + step) * verticalStep;
    node.x = (node.rank - minRank + 1) * horizontalStep;
  });

  return graph;
};

const blockHash = (block: BlockInfo) =>
  encodeBase16(block.getSummary()!.getBlockHash_asU8());

const keyBlockHash = (block: BlockInfo) =>
  encodeBase16(block.getSummary()!.getHeader()!.getKeyBlockHash_asU8());

const isBlock = (block: BlockInfo) =>
  block.getSummary()!.getHeader()!.getMessageType() === Block.MessageType.BLOCK;

const isBallot = (block: BlockInfo) => !isBlock(block);

const isFinalized = (block: BlockInfo) =>
  block.getStatus()!.getFinality() === BlockInfo.Status.Finality.FINALIZED;

const isOrphaned = (block: BlockInfo) =>
  isBlock(block) && block.getStatus()!.getFinality() === BlockInfo.Status.Finality.ORPHANED;

const validatorHash = (block: BlockInfo) =>
  encodeBase16(
    block
      .getSummary()!
      .getHeader()!
      .getValidatorPublicKey_asU8()
  );

/** Shorten lines by a fixed amount so that the line doesn't stick out from under the arrows tip. */
const shorten = (d: any, by: number) => {
  let length = Math.sqrt(
    Math.pow(d.target.x - d.source.x, 2) + Math.pow(d.target.y - d.source.y, 2)
  );
  return Math.max(0, (length - by) / length);
};

/** String hash for consistent colors. Same as Java. */
const hashCode = (s: string) => {
  let hash = 0;
  if (s.length === 0) return hash;
  for (let i = 0; i < s.length; i++) {
    let chr = s.charCodeAt(i);
    hash = (hash << 5) - hash + chr;
    hash |= 0; // Convert to 32bit integer
  }
  return hash;
};

const consistentColor = (colors: readonly string[]) => {
  // Display each validator with its own color.
  // https://www.d3-graph-gallery.com/graph/custom_color.html
  // http://bl.ocks.org/curran/3094b37e63b918bab0a06787e161607b
  // This can be used like `color(x.validator)` but it changes depending on which validators are on the screen.
  // const color = d3.scaleOrdinal(d3.schemeCategory10);
  // This can be used with a numeric value:
  // const hashRange: [number, number] = [-2147483648, 2147483647];
  //   d3.scaleSequential(d3.interpolateSpectral).domain(hashRange),
  const cl = colors.length;
  return (s: string) => {
    const h = hashCode(s);
    const c = Math.abs(h % cl);
    return colors[c];
  };
};

function arraysEqual<T>(a: T[] | null, b: T[] | null) {
  if (a === b) return true;
  if (a == null || b == null) return false;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; ++i) {
    if (a[i] !== b[i]) return false;
  }
  return true;
}
