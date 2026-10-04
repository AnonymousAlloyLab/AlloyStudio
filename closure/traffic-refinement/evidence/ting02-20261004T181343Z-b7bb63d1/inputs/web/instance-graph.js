// Concrete Alloy instances only: every mark is derived from the supplied state.
// This module does not inspect predicates, infer omitted tuples, or fetch data.
const SVG_NS = 'http://www.w3.org/2000/svg';
const COLORS = ['#2463a2', '#9d3f62', '#23734d', '#8554a2', '#a65a18', '#147881', '#70583c', '#555ea8'];
let renderSequence = 0;
let resizeListening = false;
let resizeQueued = false;

function centerDiagram(scroll) {
  if (scroll.isConnected && scroll.clientWidth > 0) {
    scroll.scrollLeft = Math.max(0, (scroll.scrollWidth - scroll.clientWidth) / 2);
    const viewport = scroll.getBoundingClientRect();
    const cards = [...scroll.querySelectorAll('.instance-graph-node > rect')]
      .map(card => card.getBoundingClientRect())
      .filter(card => card.top >= viewport.top && card.bottom <= viewport.bottom);
    if (!cards.some(card => card.left >= viewport.left && card.right <= viewport.right)) {
      // A narrow screen can center the gap between two columns. Bring the
      // nearest complete object into view instead of presenting an empty gap.
      const center = viewport.left + viewport.width / 2;
      cards.sort((a, b) => Math.abs((a.left + a.right) / 2 - center) - Math.abs((b.left + b.right) / 2 - center));
      if (cards.length) scroll.scrollLeft += (cards[0].left + cards[0].right) / 2 - center;
    }
    const hint = scroll.previousElementSibling;
    if (hint?.classList.contains('instance-graph-scroll-hint')) {
      hint.hidden = scroll.scrollWidth <= scroll.clientWidth + 1
        && scroll.scrollHeight <= scroll.clientHeight + 1;
    }
  }
}

function listenForResize() {
  if (resizeListening) return;
  resizeListening = true;
  // One page-lifetime handler, holding no instance elements. Replaced diagrams
  // need no listener cleanup, and orientation changes keep a visible center.
  window.addEventListener('resize', () => {
    if (resizeQueued) return;
    resizeQueued = true;
    requestAnimationFrame(() => {
      resizeQueued = false;
      document.querySelectorAll('.instance-graph-scroll').forEach(centerDiagram);
    });
  });
}

/** Build a bounded graph without losing tuple order or merging distinct atoms. */
export function buildInstanceGraph(stateData, options = {}) {
  const maxNodes = Math.max(1, Math.min(80, Math.floor(options.maxNodes ?? 40)));
  const maxTuples = Math.max(1, Math.min(100, Math.floor(options.maxTuples ?? 48)));
  const allNodes = new Map();
  function atom(value, signature) {
    let entry = allNodes.get(value);
    if (!entry) {
      entry = { id: `atom-${allNodes.size}`, atom: value, signatures: [], kind: 'value' };
      allNodes.set(value, entry);
    }
    if (signature !== undefined && !entry.signatures.includes(signature)) {
      entry.signatures.push(signature);
      entry.kind = 'atom';
    }
    return entry;
  }
  const signatures = stateData.signatures.map((signature, index) => {
    for (const value of signature.atoms) atom(value, signature.label);
    return { label: signature.label, index, count: signature.atoms.length };
  });
  const relations = stateData.relations.map((relation, index) => ({
    index, label: relation.label, arity: relation.arity, tupleCount: relation.tuples.length,
  }));
  // Include relation-only values (for example integers) in the atom inventory.
  for (const relation of stateData.relations) {
    for (const tuple of relation.tuples) for (const value of tuple) atom(value);
  }
  const selected = options.relationIndex === undefined || options.relationIndex === null
    ? null : Number(options.relationIndex);
  const displayed = new Map();
  const tuples = [];
  let totalTuples = 0;
  for (let relationIndex = 0; relationIndex < stateData.relations.length; relationIndex += 1) {
    if (selected !== null && relationIndex !== selected) continue;
    const relation = stateData.relations[relationIndex];
    totalTuples += relation.tuples.length;
    for (let tupleIndex = 0; tupleIndex < relation.tuples.length; tupleIndex += 1) {
      const values = relation.tuples[tupleIndex];
      const newValues = new Set(values.filter(value => !displayed.has(value)));
      if (tuples.length >= maxTuples || displayed.size + newValues.size > maxNodes) continue;
      for (const value of values) displayed.set(value, allNodes.get(value));
      tuples.push({ id: `tuple-${relationIndex}-${tupleIndex}`, relationIndex, tupleIndex,
        relation: relation.label, arity: values.length, atoms: [...values],
        nodeIds: values.map(value => allNodes.get(value).id) });
    }
  }
  // Keep isolated atoms visible too; a graph without arrows can still be an instance.
  for (const [value, entry] of allNodes) {
    if (displayed.size >= maxNodes) break;
    if (!displayed.has(value)) displayed.set(value, entry);
  }
  return { nodes: [...displayed.values()], tuples, signatures, relations,
    counts: { nodes: allNodes.size, tuples: totalTuples, shownNodes: displayed.size, shownTuples: tuples.length },
    limited: displayed.size < allNodes.size || tuples.length < totalTuples,
    relationIndex: selected };
}

function html(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function svg(tag, attributes = {}, text) {
  const element = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  if (text !== undefined) element.textContent = text;
  return element;
}

function tupleDescription(tuple) {
  const columns = tuple.atoms.map((value, index) => `column ${index + 1}: ${value}`).join('; ');
  return `${tuple.relation}, tuple ${tuple.tupleIndex + 1} (${columns}).`;
}

function activate(group, description, details) {
  group.setAttribute('role', 'button');
  group.setAttribute('tabindex', '0');
  group.setAttribute('aria-label', description);
  group.setAttribute('aria-pressed', 'false');
  const show = () => {
    for (const previous of group.ownerSVGElement?.querySelectorAll('[aria-pressed="true"]') || []) {
      previous.setAttribute('aria-pressed', 'false');
    }
    group.setAttribute('aria-pressed', 'true');
    details.textContent = description;
  };
  group.addEventListener('click', show);
  group.addEventListener('focus', show);
  group.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      show();
    }
  });
}

// All text owns measured space before any connection is routed. A small local
// orthogonal router treats both cards and labels as obstacles; crossings between
// connections are permitted, but connections never run through someone else's text.
const STEP = 16;
const snap = value => Math.ceil(value / (STEP * 2)) * STEP * 2;
function textMetrics() {
  const context = document.createElement('canvas').getContext('2d');
  const family = getComputedStyle(document.documentElement).getPropertyValue('--mono').trim() || 'monospace';
  return (text, size = 13, weight = 500) => {
    context.font = `${weight} ${size}px ${family}`;
    return context.measureText(text).width;
  };
}
function fitLabel(text, measure, width, size, weight) {
  if (measure(text, size, weight) <= width) return text;
  const characters = Array.from(text);
  while (characters.length && measure(`${characters.join('')}…`, size, weight) > width) characters.pop();
  return `${characters.join('')}…`;
}
function intersects(a, b, gap = 0) {
  return Math.abs(a.x - b.x) < (a.width + b.width) / 2 + gap
    && Math.abs(a.y - b.y) < (a.height + b.height) / 2 + gap;
}
function layout(graph) {
  const measure = textMetrics();
  const entities = graph.nodes.map(node => {
    const title = fitLabel(node.atom, measure, 224, 15, 600);
    const memberships = node.signatures.length ? node.signatures : ['relation value'];
    const lines = [];
    for (const membership of memberships) {
      const text = fitLabel(membership, measure, 224, 12, 400);
      const previous = lines.at(-1);
      if (previous && measure(`${previous} · ${text}`, 12, 400) <= 224) lines[lines.length - 1] += ` · ${text}`;
      else lines.push(text);
    }
    // Full memberships remain accessible in the details and exact table.
    if (lines.length > 3) lines.splice(2, lines.length - 2, '… more memberships');
    return { id: node.id, title, lines,
      width: Math.max(160, snap(Math.max(measure(title, 15, 600), ...lines.map(line => measure(line, 12, 400))) + 32)),
      height: snap(42 + lines.length * 17) };
  });
  for (const tuple of graph.tuples) {
    if (tuple.arity !== 2) {
      const title = fitLabel(`${tuple.relation} #${tuple.tupleIndex + 1}`, measure, 224, 13, 500);
      entities.push({ id: tuple.id, title, lines: [], width: Math.max(128, snap(measure(title) + 32)), height: 64 });
    }
  }
  // Related types occupy columns when there are a few balanced groups. This
  // reduces criss-crossing in models such as Component → Workstation → Worker.
  // Membership determines placement only; it never adds an inferred relation.
  const groups = new Map();
  for (const node of graph.nodes) {
    const membership = [...node.signatures].sort((a, b) =>
      graph.signatures.find(signature => signature.label === b).count
      - graph.signatures.find(signature => signature.label === a).count)[0] ?? '';
    if (!groups.has(membership)) groups.set(membership, { ids: [], flow: 0 });
    groups.get(membership).ids.push(node.id);
  }
  const groupOf = new Map([...groups.values()].flatMap(group => group.ids.map(id => [id, group])));
  for (const tuple of graph.tuples) {
    if (tuple.arity === 2 && groupOf.get(tuple.nodeIds[0]) !== groupOf.get(tuple.nodeIds[1])) {
      groupOf.get(tuple.nodeIds[0]).flow += 1;
      groupOf.get(tuple.nodeIds[1]).flow -= 1;
    }
  }
  const grouped = entities.length === graph.nodes.length && groups.size >= 2 && groups.size <= 4
    && Math.max(...[...groups.values()].map(group => group.ids.length)) <= Math.ceil(Math.sqrt(entities.length)) + 1;
  const columns = grouped ? groups.size : Math.min(4, Math.max(1, Math.ceil(Math.sqrt(entities.length))));
  const slots = new Map();
  if (grouped) [...groups.values()].sort((a, b) => b.flow - a.flow).forEach((group, column) =>
    group.ids.forEach((id, row) => slots.set(id, { column, row })));
  else entities.forEach((entity, index) => slots.set(entity.id, { column: index % columns, row: Math.floor(index / columns) }));
  const cellWidth = Math.max(192, ...entities.map(entity => entity.width)) + 160;
  const cellHeight = Math.max(64, ...entities.map(entity => entity.height)) + 160;
  const width = Math.max(760, snap(columns * cellWidth + 64));
  let height = Math.max(352, snap((Math.max(...[...slots.values()].map(slot => slot.row)) + 1) * cellHeight + 64));
  const positions = new Map(entities.map(entity => [entity.id, { ...entity,
    x: Math.round((width - (columns - 1) * cellWidth) / 2 / STEP) * STEP + slots.get(entity.id).column * cellWidth,
    y: 128 + slots.get(entity.id).row * cellHeight }]));
  const links = graph.tuples.flatMap(tuple => tuple.arity === 2
    ? [{ id: tuple.id, tuple, source: tuple.nodeIds[0], target: tuple.nodeIds[1], text: tuple.relation }]
    : tuple.nodeIds.map((id, index) => ({ id: `${tuple.id}-${index}`, tuple,
      source: tuple.id, target: id, column: index + 1, text: String(index + 1) })));
  const obstacles = [...positions.values()];
  // Place wide labels first, using deterministic nearest-free-space search.
  // A label never gets silently stacked over another label when a graph is dense.
  for (const link of [...links].sort((a, b) => measure(b.text) - measure(a.text))) {
    const source = positions.get(link.source), target = positions.get(link.target);
    const text = fitLabel(link.text, measure, 240, 13, 500);
    const box = { id: `label-${link.id}`, text, width: Math.max(32, snap(measure(text) + 24)), height: 32 };
    const ideal = source === target ? { x: source.x, y: source.y - source.height / 2 - 72 }
      : { x: (source.x + target.x) / 2, y: (source.y + target.y) / 2 };
    let best;
    while (!best) {
      for (let y = 48; y <= height - 48; y += STEP * 2) {
        for (let x = snap(box.width / 2 + 32); x <= width - box.width / 2 - 32; x += STEP * 2) {
          const candidate = { ...box, x, y };
          const score = (x - ideal.x) ** 2 + (y - ideal.y) ** 2;
          if ((!best || score < best.score) && !obstacles.some(other => intersects(candidate, other, 24))) {
            best = { ...candidate, score };
          }
        }
      }
      if (!best) height += 128;
    }
    link.label = best;
    obstacles.push(best);
  }
  const router = routingGrid(width, height, obstacles);
  const usedPorts = new Map();
  function ports(box, toward, key) {
    const candidates = [];
    for (let x = box.x - box.width / 2 + STEP; x < box.x + box.width / 2; x += STEP) {
      candidates.push({ x, y: box.y - box.height / 2 - STEP, bx: x, by: box.y - box.height / 2 - 3, side: 'top' });
      candidates.push({ x, y: box.y + box.height / 2 + STEP, bx: x, by: box.y + box.height / 2 + 3, side: 'bottom' });
    }
    for (let y = box.y - box.height / 2 + STEP; y < box.y + box.height / 2; y += STEP) {
      candidates.push({ x: box.x - box.width / 2 - STEP, y, bx: box.x - box.width / 2 - 3, by: y, side: 'left' });
      candidates.push({ x: box.x + box.width / 2 + STEP, y, bx: box.x + box.width / 2 + 3, by: y, side: 'right' });
    }
    return candidates.filter(point => !router.blocked(point)).sort((a, b) => {
      const cost = p => Math.abs(p.x - toward.x) + Math.abs(p.y - toward.y)
        + (usedPorts.get(`${key}:${p.x},${p.y}`) || 0) * 1000
        + Math.abs(p.x - box.x) * 0.01 + Math.abs(p.y - box.y) * 0.01;
      return cost(a) - cost(b);
    });
  }
  const occupy = (box, port) => {
    const key = `${box.id}:${port.x},${port.y}`;
    usedPorts.set(key, (usedPorts.get(key) || 0) + 1);
  };
  for (const link of links) {
    const source = positions.get(link.source), target = positions.get(link.target), label = link.label;
    const start = ports(source, label, source.id)[0];
    occupy(source, start);
    const end = ports(target, label, target.id)[0];
    occupy(target, end);
    const entry = ports(label, source, label.id)[0];
    occupy(label, entry);
    const exit = ports(label, target, label.id)[0];
    const first = router.route(start, entry), last = router.route(exit, end);
    const points = [{ x: start.bx, y: start.by }, ...first,
      { x: entry.bx, y: entry.by }, { x: label.x, y: label.y },
      { x: exit.bx, y: exit.by }, ...last, { x: end.bx, y: end.by }];
    link.geometry = { path: points.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '), label };
  }
  return { positions, width, height, links };
}

// A* on a bounded orthogonal grid. Occupied tracks cost extra, so parallel and
// opposite arrows separate instead of collapsing into the same stroke. All
// cards/labels are hard obstacles, including labels of not-yet-routed edges.
function routingGrid(width, height, obstacles) {
  const columns = Math.floor(width / STEP) + 1, rows = Math.floor(height / STEP) + 1;
  const size = columns * rows;
  const blocked = new Uint8Array(size), congestion = new Uint16Array(size);
  for (const box of obstacles) {
    const left = Math.max(0, Math.ceil((box.x - box.width / 2 - 6) / STEP));
    const right = Math.min(columns - 1, Math.floor((box.x + box.width / 2 + 6) / STEP));
    const top = Math.max(0, Math.ceil((box.y - box.height / 2 - 6) / STEP));
    const bottom = Math.min(rows - 1, Math.floor((box.y + box.height / 2 + 6) / STEP));
    for (let y = top; y <= bottom; y += 1) for (let x = left; x <= right; x += 1) blocked[y * columns + x] = 1;
  }
  const key = point => Math.round(point.y / STEP) * columns + Math.round(point.x / STEP);
  return { blocked: point => point.x < STEP || point.y < STEP || point.x > width - STEP || point.y > height - STEP || !!blocked[key(point)],
    route(start, end) {
      const first = key(start), goal = key(end), goalX = goal % columns, goalY = Math.floor(goal / columns);
      const costs = new Float64Array(size).fill(Infinity), previous = new Int32Array(size).fill(-1);
      const heap = [];
      const push = (id, cost) => {
        const item = { id, cost, priority: cost + Math.abs(id % columns - goalX) + Math.abs(Math.floor(id / columns) - goalY) };
        let index = heap.length; heap.push(item);
        while (index) {
          const parent = (index - 1) >> 1;
          if (heap[parent].priority <= item.priority) break;
          heap[index] = heap[parent]; index = parent;
        }
        heap[index] = item;
      };
      const pop = () => {
        const item = heap[0], tail = heap.pop();
        if (heap.length) {
          let index = 0;
          while (index * 2 + 1 < heap.length) {
            let child = index * 2 + 1;
            if (child + 1 < heap.length && heap[child + 1].priority < heap[child].priority) child += 1;
            if (heap[child].priority >= tail.priority) break;
            heap[index] = heap[child]; index = child;
          }
          heap[index] = tail;
        }
        return item;
      };
      costs[first] = 0; push(first, 0);
      while (heap.length) {
        const { id, cost } = pop();
        if (cost !== costs[id]) continue;
        if (id === goal) break;
        const x = id % columns, y = Math.floor(id / columns);
        const neighbors = [x + 1 < columns ? id + 1 : -1, y + 1 < rows ? id + columns : -1,
          x ? id - 1 : -1, y ? id - columns : -1];
        for (const next of neighbors) {
          if (next < 0 || blocked[next]) continue;
          const turn = previous[id] >= 0 && id - previous[id] !== next - id ? 0.4 : 0;
          const candidate = cost + 1 + turn + congestion[next] * 8;
          if (candidate >= costs[next]) continue;
          costs[next] = candidate; previous[next] = id; push(next, candidate);
        }
      }
      // Gaps between every obstacle are at least one free grid track. No
      // straight-line fallback is allowed: it could cut through someone else's text.
      if (!Number.isFinite(costs[goal])) throw new Error('No clear instance connection route');
      const points = [];
      for (let id = goal; id >= 0; id = previous[id]) {
        points.push({ x: id % columns * STEP, y: Math.floor(id / columns) * STEP });
        congestion[id] += 1;
        if (id === first) break;
      }
      return points.reverse();
    } };
}

function drawPath(group, geometry, color, marker, attributes = {}) {
  group.append(svg('path', { class: 'instance-graph-edge-hit', d: geometry.path,
    stroke: 'transparent', 'stroke-width': 16, fill: 'none', 'pointer-events': 'stroke' }));
  group.append(svg('path', { class: 'instance-graph-edge', d: geometry.path,
    stroke: color, 'stroke-width': 2, fill: 'none', 'marker-end': `url(#${marker})`, ...attributes }));
}

function draw(graph, prefix, details) {
  const { positions, width, height, links } = layout(graph);
  const canvas = svg('svg', { class: 'instance-graph-canvas', width, height, viewBox: `0 0 ${width} ${height}`,
    role: 'group', 'aria-labelledby': `${prefix}-title ${prefix}-description` });
  canvas.append(svg('title', { id: `${prefix}-title` }, 'Concrete instance diagram'));
  canvas.append(svg('desc', { id: `${prefix}-description` },
    `${graph.nodes.length} atoms or values and ${graph.tuples.length} relation tuples shown. `
    + 'Boxes are atoms or values. Arrows are two-column tuples. Labeled tuple boxes connect numbered columns for other arities. '
    + 'Focus or select an atom or relation to read its exact details below. Position and distance carry no meaning.'));
  const defs = svg('defs');
  canvas.append(defs);
  const relationColors = new Map();
  for (const tuple of graph.tuples) {
    if (relationColors.has(tuple.relationIndex)) continue;
    const color = COLORS[tuple.relationIndex % COLORS.length];
    relationColors.set(tuple.relationIndex, color);
    const marker = svg('marker', { id: `${prefix}-arrow-${tuple.relationIndex}`, viewBox: '0 0 10 10',
      refX: 9, refY: 5, markerWidth: 6, markerHeight: 6, orient: 'auto-start-reverse' });
    marker.append(svg('path', { d: 'M 0 0 L 10 5 L 0 10 z', fill: color }));
    defs.append(marker);
  }
  for (const tuple of graph.tuples) {
    const color = relationColors.get(tuple.relationIndex);
    const marker = `${prefix}-arrow-${tuple.relationIndex}`;
    const group = svg('g', { class: 'instance-graph-tuple', 'data-tuple-id': tuple.id,
      'data-arity': tuple.arity, 'data-relation-index': tuple.relationIndex, 'data-tuple-index': tuple.tupleIndex });
    const description = tupleDescription(tuple);
    group.append(svg('title', {}, description));
    activate(group, description, details);
    for (const link of links.filter(item => item.tuple === tuple)) {
      const attributes = link.column === undefined
        ? { 'data-source': tuple.atoms[0], 'data-target': tuple.atoms[1] }
        : { 'data-column': link.column, 'data-target': tuple.atoms[link.column - 1] };
      drawPath(group, link.geometry, color, marker, attributes);
    }
    // Labels sit in reserved obstacle rectangles; they are not placed using an
    // edge midpoint after routing. Draw them above their own connection stroke.
    for (const link of links.filter(item => item.tuple === tuple)) {
      const label = link.label;
      group.append(svg('rect', { class: 'instance-graph-label-background',
        x: label.x - label.width / 2, y: label.y - label.height / 2,
        width: label.width, height: label.height, rx: 6, fill: '#ffffff', stroke: '#dce5ee' }));
      group.append(svg('text', { class: link.column === undefined ? 'instance-graph-edge-label' : 'instance-graph-column-label',
        x: label.x, y: label.y + 4, 'text-anchor': 'middle', fill: color,
        ...(link.column === undefined ? {} : { 'data-column': link.column }) }, label.text));
    }
    if (tuple.arity !== 2) {
      const junction = positions.get(tuple.id);
      const box = svg('g', { class: 'instance-graph-junction' });
      box.append(svg('rect', { x: junction.x - junction.width / 2, y: junction.y - junction.height / 2,
        width: junction.width, height: junction.height, rx: 5, fill: '#ffffff', stroke: color,
        'stroke-width': 2, 'stroke-dasharray': '5 3' }));
      box.append(svg('text', { class: 'instance-graph-junction-label', x: junction.x, y: junction.y + 5,
        'text-anchor': 'middle', fill: color, 'font-size': 13 }, junction.title));
      group.append(box);
    }
    canvas.append(group);
  }
  for (const node of graph.nodes) {
    const point = positions.get(node.id);
    const firstSignature = graph.signatures.find(signature => signature.label === node.signatures[0]);
    const color = firstSignature ? COLORS[firstSignature.index % COLORS.length] : '#52616b';
    const group = svg('g', { class: `instance-graph-node instance-graph-node-${node.kind}`, 'data-atom': node.atom });
    const membership = node.signatures.length ? `Member of ${node.signatures.join(', ')}.` : 'Value appearing in a relation; no named signature membership is listed.';
    const description = `${node.atom}. ${membership}`;
    group.append(svg('title', {}, description));
    activate(group, description, details);
    group.append(svg('rect', { x: point.x - point.width / 2, y: point.y - point.height / 2,
      width: point.width, height: point.height, rx: node.kind === 'value' ? 5 : 10,
      fill: '#ffffff', stroke: color, 'stroke-width': 1.5 }));
    const titleY = point.y - (point.lines.length * 17) / 2 + 5;
    group.append(svg('text', { class: 'instance-graph-node-label', x: point.x, y: titleY,
      'text-anchor': 'middle' }, point.title));
    point.lines.forEach((line, index) => group.append(svg('text', {
      class: 'instance-graph-node-membership', x: point.x, y: titleY + (index + 1) * 17,
      'text-anchor': 'middle', fill: color }, line)));
    canvas.append(group);
  }
  return canvas;
}

/** Render one state. Exact tables remain the companion source for large instances. */
export function renderInstanceGraph(stateData) {
  listenForResize();
  const prefix = `instance-graph-${++renderSequence}`;
  const root = html('section', 'instance-graph');
  root.setAttribute('aria-label', 'Instance visualization');
  const intro = html('div', 'instance-graph-intro');
  intro.append(html('h4', null, `Instance diagram · State ${(stateData.index ?? 0) + 1}`), html('p', null,
    'Explore one possible world. Select an object or connection to read its exact details.'));
  root.append(intro);
  const toolbar = html('div', 'instance-graph-toolbar');
  const label = html('label', null, 'Show relations: ');
  label.htmlFor = `${prefix}-filter`;
  const select = html('select', 'instance-graph-relation-filter');
  select.id = `${prefix}-filter`;
  select.append(html('option', null, 'All relations'));
  select.firstElementChild.value = 'all';
  stateData.relations.forEach((relation, index) => {
    const option = html('option', null, `${relation.label} (${relation.tuples.length} tuples)`);
    option.value = String(index);
    select.append(option);
  });
  toolbar.append(label, select);
  root.append(toolbar);
  const picture = html('div', 'instance-graph-picture');
  root.append(picture);
  const legend = html('ul', 'instance-graph-legend');
  legend.append(html('li', null, 'Solid boxes: individual objects (atoms) or values; type memberships appear below their names.'),
    html('li', null, 'Labeled arrows: a two-column relation, from column 1 to column 2. A loop returns to the same object.'),
    html('li', null, 'Dashed boxes: one tuple with numbered column positions, not additional objects.'));
  const layoutNote = html('p', 'instance-graph-layout-note',
    'Positions and distances have no meaning. Labels identify every connection; colors only help distinguish them. Full names and tuples are in the exact data below.');
  const details = html('p', 'instance-graph-details', 'Select an atom or relation to read its details here.');
  details.setAttribute('aria-live', 'polite');
  details.setAttribute('aria-atomic', 'true');
  root.append(legend, layoutNote, details);
  function refresh() {
    const graph = buildInstanceGraph(stateData, { relationIndex: select.value === 'all' ? null : Number(select.value) });
    const content = [];
    if (graph.limited) content.push(html('p', 'instance-graph-limit',
      `Limited picture: showing ${graph.counts.shownNodes} of ${graph.counts.nodes} atoms/values and `
      + `${graph.counts.shownTuples} of ${graph.counts.tuples} tuples for the selected relations. The exact data tables retain all supplied data.`));
    if (!graph.nodes.length) {
      content.push(html('p', 'instance-graph-empty', 'This state has no atoms or relation values to draw.'));
    } else {
      if (!graph.tuples.length) content.push(html('p', 'instance-graph-empty',
        'There are no tuples in the selected relations. The boxes show the atoms and values in this state.'));
      const scroll = html('div', 'instance-graph-scroll');
      scroll.setAttribute('tabindex', '0');
      scroll.setAttribute('role', 'region');
      scroll.setAttribute('aria-label', 'Instance diagram; scroll to explore when needed');
      scroll.append(draw(graph, prefix, details));
      content.push(html('p', 'instance-graph-scroll-hint', 'Scroll to explore the diagram.'));
      content.push(scroll);
      // renderInstanceGraph returns a detached element. Wait for its caller to
      // attach it before measuring, and ignore an already replaced diagram.
      // Centering brings the first atom of a small ring (or a lone atom) into
      // view on narrow screens without shrinking its labels.
      requestAnimationFrame(() => centerDiagram(scroll));
    }
    const emptySignatures = graph.signatures.filter(signature => !signature.count).map(signature => signature.label);
    if (emptySignatures.length) content.push(html('p', 'instance-graph-empty', `Signatures with no atoms: ${emptySignatures.join(', ')}.`));
    picture.replaceChildren(...content);
    details.textContent = 'Select an atom or relation to read its details here.';
  }
  select.addEventListener('change', refresh);
  refresh();
  return root;
}
