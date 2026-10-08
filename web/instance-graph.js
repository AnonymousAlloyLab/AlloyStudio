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
    const visibleCards = [...scroll.querySelectorAll('.instance-graph-node > rect')]
      .map(card => card.getBoundingClientRect())
      .filter(card => card.top >= viewport.top && card.bottom <= viewport.bottom);
    const firstRow = Math.min(...visibleCards.map(card => card.top));
    const cards = visibleCards.filter(card => Math.abs(card.top - firstRow) < 1);
    if (!cards.some(card => card.left >= viewport.left && card.right <= viewport.right)) {
      // A narrow screen can center the gap between two columns. Bring the
      // nearest complete object in the uppermost visible row into view, so the
      // hierarchy begins with an object rather than an empty gap between roots.
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

function highlightContext(canvas, details, description, context, selected) {
  const root = canvas.closest('.instance-graph') || canvas;
  for (const previous of root.querySelectorAll('[aria-pressed="true"]')) {
    previous.setAttribute('aria-pressed', 'false');
  }
  selected?.setAttribute('aria-pressed', 'true');
  details.replaceChildren(html('span', 'instance-graph-selection-description', description));
  const selectedNodes = new Set(context.nodes);
  const selectedTuples = new Set(context.tuples);
  for (const element of canvas.querySelectorAll('.instance-graph-node, .instance-graph-tuple')) {
    const related = element.hasAttribute('data-atom')
      ? selectedNodes.has(element.getAttribute('data-atom'))
      : selectedTuples.has(element.getAttribute('data-tuple-id'));
    element.classList.toggle('instance-graph-context-related', related);
    element.classList.toggle('instance-graph-context-muted', !related);
  }
  const relationIndexes = new Set(context.relationIndexes || []);
  for (const item of root.querySelectorAll('.instance-graph-relation-key > li')) {
    item.classList.toggle('instance-graph-relation-focused', relationIndexes.has(Number(item.dataset.relationIndex)));
  }
  if (context.relations?.length) {
    const relations = html('span', 'instance-graph-object-relations');
    relations.append(html('span', null, 'Highlight a relation: '));
    for (const relation of context.relations) {
      const button = html('button', 'instance-graph-object-relation', relation.label);
      button.type = 'button'; button.dataset.relationIndex = String(relation.index);
      button.setAttribute('aria-label', `Highlight all visible tuples of relation ${relation.label}.`);
      button.addEventListener('click', () => context.selectRelation(relation.index));
      relations.append(button);
    }
    details.append(relations);
  }
}

function activate(group, description, details, context) {
  group.setAttribute('role', 'button');
  group.setAttribute('tabindex', '0');
  group.setAttribute('aria-label', description);
  group.setAttribute('aria-pressed', 'false');
  const show = () => {
    const canvas = group.ownerSVGElement;
    if (canvas) highlightContext(canvas, details, description, context, group);
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

// Hierarchy is a reading aid only. Its edges are supplied relation tuples, not
// inferred parenthood: condense cycles first, then rank the resulting DAG.
const STEP = 16;
const snap = value => Math.ceil(value / (STEP * 2)) * STEP * 2;
function textMetrics() {
  const context = document.createElement('canvas').getContext('2d');
  const family = getComputedStyle(document.documentElement).getPropertyValue('--mono').trim() || 'monospace';
  return (text, size = 11, weight = 500) => {
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
function middleLabel(text, measure, width) {
  if (measure(text, 11, 600) <= width) return text;
  const characters = Array.from(text);
  let left = Math.ceil(characters.length / 2), right = characters.length - left;
  while (left + right > 1) {
    const candidate = `${characters.slice(0, left).join('')}…${characters.slice(characters.length - right).join('')}`;
    if (measure(candidate, 11, 600) <= width) return candidate;
    if (left > right) left -= 1; else right -= 1;
  }
  return '…';
}
function relationLabels(graph, measure) {
  const labels = new Map(graph.relations.map(relation => [relation.index, middleLabel(relation.label, measure, 216)]));
  for (const relation of graph.relations) {
    const collision = graph.relations.some(other => other.index !== relation.index
      && other.label !== relation.label && labels.get(other.index) === labels.get(relation.index));
    if (collision) {
      // Never make two distinct relation names look identical after elision.
      const shared = labels.get(relation.index);
      for (const other of graph.relations) if (labels.get(other.index) === shared) labels.set(other.index, other.label);
    }
  }
  return labels;
}
function atomLabel(atom, measure) {
  const named = /^(.*)\$(\d+)$/.exec(atom);
  if (!named) return fitLabel(atom, measure, 144, 14, 600);
  const suffix = ` ${named[2]}`;
  return fitLabel(named[1], measure, 144 - measure(suffix, 14, 600), 14, 600) + suffix;
}
function nodeLabel(node, measure) {
  if (!/^[+-]?\d+$/.test(node.atom)) return atomLabel(node.atom, measure);
  // A number-only object box is ambiguous to a novice. Use an actual supplied
  // membership if present; relation-only numbers remain values, not inferred Ints.
  const prefix = node.signatures[0] || 'Value';
  const suffix = ` · ${node.atom}`;
  const remaining = 144 - measure(suffix, 14, 600);
  if (remaining < measure('V…', 14, 600)) {
    return fitLabel(`Value · ${node.atom}`, measure, 144, 14, 600);
  }
  return fitLabel(prefix, measure, remaining, 14, 600) + suffix;
}
function intersects(a, b, gap = 0) {
  return Math.abs(a.x - b.x) < (a.width + b.width) / 2 + gap
    && Math.abs(a.y - b.y) < (a.height + b.height) / 2 + gap;
}
function hierarchy(entities, graph) {
  const outgoing = new Map(entities.map(entity => [entity.id, new Set()]));
  const incoming = new Map(entities.map(entity => [entity.id, new Set()]));
  const connect = (source, target) => { outgoing.get(source).add(target); incoming.get(target).add(source); };
  for (const tuple of graph.tuples) {
    if (tuple.arity === 2) connect(tuple.nodeIds[0], tuple.nodeIds[1]);
    else if (tuple.nodeIds.length) {
      // The first column leads to the tuple hub in this layout only. Every
      // actual rendered spoke remains hub → its original numbered column.
      connect(tuple.nodeIds[0], tuple.id);
      for (const target of tuple.nodeIds.slice(1)) connect(tuple.id, target);
    }
  }
  let next = 0;
  const index = new Map(), low = new Map(), stack = [], active = new Set(), components = [];
  function visit(id) {
    index.set(id, next); low.set(id, next); next += 1; stack.push(id); active.add(id);
    for (const target of outgoing.get(id)) {
      if (!index.has(target)) { visit(target); low.set(id, Math.min(low.get(id), low.get(target))); }
      else if (active.has(target)) low.set(id, Math.min(low.get(id), index.get(target)));
    }
    if (low.get(id) === index.get(id)) {
      const members = [];
      let member;
      do { member = stack.pop(); active.delete(member); members.push(member); } while (member !== id);
      components.push(members);
    }
  }
  for (const entity of entities) if (!index.has(entity.id)) visit(entity.id);
  const order = new Map(entities.map((entity, position) => [entity.id, position]));
  components.forEach(members => members.sort((a, b) => order.get(a) - order.get(b)));
  components.sort((a, b) => order.get(a[0]) - order.get(b[0]));
  const componentOf = new Map(components.flatMap((members, position) => members.map(id => [id, position])));
  const children = components.map(() => new Set()), indegree = components.map(() => 0), ranks = components.map(() => 0);
  for (const [source, targets] of outgoing) for (const target of targets) {
    const first = componentOf.get(source), second = componentOf.get(target);
    if (first !== second && !children[first].has(second)) { children[first].add(second); indegree[second] += 1; }
  }
  const ready = components.map((_, position) => position).filter(position => !indegree[position]);
  while (ready.length) {
    const first = ready.shift();
    for (const second of children[first]) {
      ranks[second] = Math.max(ranks[second], ranks[first] + 1);
      indegree[second] -= 1;
      if (!indegree[second]) { ready.push(second); ready.sort((a, b) => a - b); }
    }
  }
  const connected = id => outgoing.get(id).size || incoming.get(id).size;
  const maximum = Math.max(0, ...ranks);
  const layers = new Map();
  components.forEach((members, component) => {
    const rank = members.some(connected) ? ranks[component] : maximum + 1;
    if (!layers.has(rank)) layers.set(rank, []);
    layers.get(rank).push({ component, members });
  });
  // Keep each cyclic component together and order independent branches by the
  // preceding layer's positions. Stable ties preserve the supplied inventory.
  const previous = new Map();
  for (const rank of [...layers.keys()].sort((a, b) => a - b)) {
    const layer = layers.get(rank);
    const mean = block => {
      const parents = block.members.flatMap(id => [...incoming.get(id)]).filter(id => previous.has(id));
      return parents.length ? parents.reduce((sum, id) => sum + previous.get(id), 0) / parents.length : Infinity;
    };
    layer.sort((a, b) => mean(a) - mean(b) || a.component - b.component);
    layer.flatMap(block => block.members).forEach((id, position) => previous.set(id, position));
  }
  return { layers, componentOf, outgoing, incoming };
}
function layout(graph) {
  const measure = textMetrics();
  const relationNames = relationLabels(graph, measure);
  const entities = graph.nodes.map(node => {
    const title = nodeLabel(node, measure);
    const primary = node.signatures[0] || 'relation value';
    const suffix = node.signatures.length > 1 ? ` +${node.signatures.length - 1} types` : '';
    const memberships = node.signatures.length ? node.signatures.join(' · ') : primary;
    const line = measure(memberships, 11, 400) <= 144 ? memberships
      : fitLabel(primary, measure, 144 - measure(suffix, 11, 400), 11, 400) + suffix;
    return { id: node.id, title, lines: [line], width: 176, height: 64 };
  });
  for (const tuple of graph.tuples) if (tuple.arity !== 2) {
    const title = `${relationNames.get(tuple.relationIndex)} #${tuple.tupleIndex + 1}`;
    entities.push({ id: tuple.id, title, lines: [],
      width: Math.max(96, snap((tuple.arity - 1) * 18 + 24), snap(measure(title, 11, 600) + 32)), height: 64 });
  }
  const { layers, componentOf } = hierarchy(entities, graph);
  const maxLayer = Math.max(1, ...[...layers.values()].map(layer => layer.reduce((sum, block) => sum + block.members.length, 0)));
  const columns = Math.min(window.innerWidth < 640 ? 2 : 4, maxLayer), cellWidth = Math.max(176, ...entities.map(entity => entity.width)) + 96;
  const width = Math.max(320, snap(columns * cellWidth + 64), snap(Math.max(0, ...[...relationNames.values()].map(name => measure(name, 11, 600))) + 160));
  const positions = new Map(), entityById = new Map(entities.map(entity => [entity.id, entity]));
  let y = 144;
  for (const rank of [...layers.keys()].sort((a, b) => a - b)) {
    const members = layers.get(rank).flatMap(block => block.members);
    const rows = Math.ceil(members.length / columns);
    for (let index = 0; index < members.length; index += 1) {
      const row = Math.floor(index / columns), rowSize = Math.min(columns, members.length - row * columns);
      const id = members[index];
      positions.set(id, { ...entityById.get(id), rank, component: componentOf.get(id),
        x: Math.round((width - (rowSize - 1) * cellWidth) / 2 / STEP) * STEP + index % columns * cellWidth,
        y: y + row * 160 });
    }
    y += rows * 160 + 16;
  }
  let height = Math.max(288, snap(Math.max(...[...positions.values()].map(point => point.y + point.height / 2)) + 64));
  const componentTop = new Map();
  for (const point of positions.values()) componentTop.set(point.component,
    Math.min(componentTop.get(point.component) ?? Infinity, point.y - point.height / 2));
  const links = graph.tuples.flatMap(tuple => tuple.arity === 2
    ? [{ id: tuple.id, tuple, source: tuple.nodeIds[0], target: tuple.nodeIds[1], text: relationNames.get(tuple.relationIndex) }]
    : tuple.nodeIds.map((id, index) => ({ id: `${tuple.id}-${index}`, tuple,
      source: tuple.id, target: id, column: index + 1, text: String(index + 1) })));
  const obstacles = [...positions.values()];
  // Only binary arrows need separate badges. Ordered spokes put their column
  // numbers inside the hub, avoiding a maze of repeated names and labels.
  for (const link of links.filter(item => item.column === undefined)) {
    const source = positions.get(link.source), target = positions.get(link.target);
    const box = { id: `label-${link.id}`, text: link.text, width: Math.max(32, snap(measure(link.text, 11, 600) + 20)), height: 24 };
    const ideal = source.component === target.component
      ? { x: (source.x + target.x) / 2, y: componentTop.get(source.component) - 48 }
      : { x: (source.x + target.x) / 2, y: (source.y + target.y) / 2 };
    let best;
    while (!best) {
      for (let by = 48; by <= height - 48; by += STEP * 2) {
        for (let bx = snap(box.width / 2 + 32); bx <= width - box.width / 2 - 32; bx += STEP * 2) {
          const candidate = { ...box, x: bx, y: by };
          const score = (bx - ideal.x) ** 2 + (by - ideal.y) ** 2;
          if ((!best || score < best.score) && !obstacles.some(other => intersects(candidate, other, 32))) best = { ...candidate, score };
        }
      }
      if (!best) height += 64;
    }
    link.label = best; obstacles.push(best);
  }
  const router = routingGrid(width, height, obstacles), usedPorts = new Map();
  function ports(box, toward, key, preferred) {
    const candidates = [];
    for (let px = box.x - box.width / 2 + STEP; px < box.x + box.width / 2; px += STEP) {
      candidates.push({ x: px, y: Math.floor((box.y - box.height / 2 - STEP) / STEP) * STEP, bx: px, by: box.y - box.height / 2 - 3, side: 'top' });
      candidates.push({ x: px, y: Math.ceil((box.y + box.height / 2 + STEP) / STEP) * STEP, bx: px, by: box.y + box.height / 2 + 3, side: 'bottom' });
    }
    for (let py = box.y - box.height / 2 + STEP; py < box.y + box.height / 2; py += STEP) {
      candidates.push({ x: Math.floor((box.x - box.width / 2 - STEP) / STEP) * STEP, y: py, bx: box.x - box.width / 2 - 3, by: py, side: 'left' });
      candidates.push({ x: Math.ceil((box.x + box.width / 2 + STEP) / STEP) * STEP, y: py, bx: box.x + box.width / 2 + 3, by: py, side: 'right' });
    }
    return candidates.filter(point => !router.blocked(point)).sort((a, b) => {
      const cost = point => Math.abs(point.x - toward.x) + Math.abs(point.y - toward.y)
        + (usedPorts.get(`${key}:${point.x},${point.y}`) || 0) * 160
        + (preferred && point.side !== preferred ? 120 : 0)
        + Math.abs(point.x - box.x) * 0.05 + Math.abs(point.y - box.y) * 0.05;
      return cost(a) - cost(b);
    });
  }
  const occupy = (box, port) => {
    const key = `${box.id}:${port.x},${port.y}`;
    usedPorts.set(key, (usedPorts.get(key) || 0) + 1);
  };
  for (const link of links) {
    const source = positions.get(link.source), target = positions.get(link.target), label = link.label;
    const sourceSide = target.y > source.y ? 'bottom' : target.y < source.y ? 'top' : null;
    const spokeX = link.column === 1 ? source.x
      : source.x + ((link.column - 2) - Math.floor((link.tuple.arity - 2) / 2)) * STEP;
    const spokeTop = link.column === 1;
    const start = link.column === undefined ? ports(source, label || target, source.id, sourceSide)[0]
      : { x: spokeX, y: source.y + (spokeTop ? -source.height / 2 - STEP : source.height / 2 + STEP),
        bx: spokeX, by: source.y + (spokeTop ? -source.height / 2 - 3 : source.height / 2 + 3) };
    occupy(source, start);
    const end = ports(target, label || source, target.id, sourceSide === 'bottom' ? 'top' : sourceSide === 'top' ? 'bottom' : null)[0];
    occupy(target, end);
    let points;
    if (label) {
      const entry = ports(label, source, label.id)[0]; occupy(label, entry);
      const exit = ports(label, target, label.id)[0];
      points = [{ x: start.bx, y: start.by }, ...router.route(start, entry),
        { x: entry.bx, y: entry.by }, { x: label.x, y: label.y },
        { x: exit.bx, y: exit.by }, ...router.route(exit, end), { x: end.bx, y: end.by }];
    } else points = [{ x: start.bx, y: start.by }, ...router.route(start, end), { x: end.bx, y: end.by }];
    // Remove collinear grid points: a connection reads as a few clear bends,
    // rather than hundreds of tiny line segments, without changing its route.
    const compact = [];
    for (const point of points) {
      const last = compact.at(-1), previous = compact.at(-2);
      if (last && previous && ((previous.x === last.x && last.x === point.x) || (previous.y === last.y && last.y === point.y))) compact.pop();
      if (!compact.length || compact.at(-1).x !== point.x || compact.at(-1).y !== point.y) compact.push(point);
    }
    link.geometry = { path: compact.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '), label };
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
          const turn = previous[id] >= 0 && id - previous[id] !== next - id ? 3 : 0;
          const candidate = cost + 1 + turn + Math.min(3, congestion[next]) * 2;
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
    + 'Read the hierarchy from top to bottom. Cycles stay grouped together; arrows retain their original directions. Focus or select an object or relation to read its exact details below. Layout does not add relationships.'));
  const selectRelation = (index, selected) => {
    const relation = graph.relations.find(item => item.index === index);
    if (!relation) return;
    const tuples = graph.tuples.filter(tuple => tuple.relationIndex === index);
    const root = canvas.closest('.instance-graph');
    const legendButton = [...root?.querySelectorAll('.instance-graph-relation-button') || []]
      .find(button => Number(button.dataset.relationIndex) === index);
    const description = `${relation.label}. Showing ${tuples.length} of ${relation.tupleCount} supplied tuples in this diagram. `
      + (tuples.length ? 'Select a labeled connection for its exact ordered columns.'
        : relation.tupleCount ? 'This relation has no tuples in the current filtered or limited picture.' : 'This relation has no tuples in this state.');
    highlightContext(canvas, details, description, {
      nodes: tuples.flatMap(tuple => tuple.atoms), tuples: tuples.map(tuple => tuple.id), relationIndexes: [index],
    }, selected || legendButton);
  };
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
    activate(group, description, details, { nodes: tuple.atoms, tuples: [tuple.id], relationIndexes: [tuple.relationIndex] });
    for (const link of links.filter(item => item.tuple === tuple)) {
      const attributes = link.column === undefined
        ? { 'data-source': tuple.atoms[0], 'data-target': tuple.atoms[1] }
        : { 'data-column': link.column, 'data-target': tuple.atoms[link.column - 1] };
      drawPath(group, link.geometry, color, marker, attributes);
    }
    // Labels sit in reserved obstacle rectangles; they are not placed using an
    // edge midpoint after routing. Draw them above their own connection stroke.
    for (const link of links.filter(item => item.tuple === tuple && item.label)) {
      const label = link.label;
      group.append(svg('rect', { class: 'instance-graph-label-background',
        x: label.x - label.width / 2, y: label.y - label.height / 2,
        width: label.width, height: label.height, rx: 5, fill: '#ffffff', stroke: '#dce5ee' }));
      group.append(svg('text', { class: link.column === undefined ? 'instance-graph-edge-label' : 'instance-graph-column-label',
        x: label.x, y: label.y + 4, 'text-anchor': 'middle', fill: color,
        ...(link.column === undefined ? {} : { 'data-column': link.column }) }, label.text));
    }
    if (tuple.arity !== 2) {
      const junction = positions.get(tuple.id);
      const box = svg('g', { class: 'instance-graph-junction', 'data-layout-rank': junction.rank, 'data-layout-component': junction.component });
      box.append(svg('rect', { x: junction.x - junction.width / 2, y: junction.y - junction.height / 2,
        width: junction.width, height: junction.height, rx: 5, fill: '#ffffff', stroke: color,
        'stroke-width': 1.5, 'stroke-dasharray': '4 3' }));
      box.append(svg('text', { class: 'instance-graph-junction-label', x: junction.x, y: junction.y + 4,
        'text-anchor': 'middle', fill: color, 'font-size': 11 }, junction.title));
      tuple.atoms.forEach((_, index) => {
        const column = index + 1;
        const x = column === 1 ? junction.x
          : junction.x + (index - 1 - Math.floor((tuple.arity - 2) / 2)) * STEP;
        box.append(svg('text', { class: 'instance-graph-column-label', x,
          y: junction.y + (column === 1 ? -18 : 22), 'data-column': column,
          'text-anchor': 'middle', fill: color }, String(column)));
      });
      group.append(box);
    }
    canvas.append(group);
  }
  for (const node of graph.nodes) {
    const point = positions.get(node.id);
    const firstSignature = graph.signatures.find(signature => signature.label === node.signatures[0]);
    const color = firstSignature ? COLORS[firstSignature.index % COLORS.length] : '#52616b';
    const group = svg('g', { class: `instance-graph-node instance-graph-node-${node.kind}`, 'data-atom': node.atom, 'data-layout-rank': point.rank, 'data-layout-component': point.component });
    const membership = node.signatures.length ? `Member of ${node.signatures.join(', ')}.` : 'Value appearing in a relation; no named signature membership is listed.';
    const adjacent = graph.tuples.filter(tuple => tuple.atoms.includes(node.atom));
    const relationIndexes = [...new Set(adjacent.map(tuple => tuple.relationIndex))];
    const relations = graph.relations.filter(relation => relationIndexes.includes(relation.index));
    const description = `${node.atom}. ${membership}`
      + (relations.length ? ` Connected by ${relations.map(relation => relation.label).join(', ')}.` : ' No connections in the current picture.');
    group.append(svg('title', {}, description));
    activate(group, description, details, { nodes: [node.atom, ...adjacent.flatMap(tuple => tuple.atoms)],
      tuples: adjacent.map(tuple => tuple.id), relationIndexes, relations, selectRelation });
    group.append(svg('rect', { x: point.x - point.width / 2, y: point.y - point.height / 2,
      width: point.width, height: point.height, rx: node.kind === 'value' ? 5 : 10,
      fill: '#ffffff', stroke: color, 'stroke-width': 1.5 }));
    const titleY = point.y - 4;
    group.append(svg('text', { class: 'instance-graph-node-label', x: point.x, y: titleY,
      'text-anchor': 'middle' }, point.title));
    point.lines.forEach((line, index) => group.append(svg('text', {
      class: 'instance-graph-node-membership', x: point.x, y: titleY + (index + 1) * 18,
      'text-anchor': 'middle', fill: color }, line)));
    canvas.append(group);
  }
  return { canvas, selectRelation };
}

/** Render one state. Exact tables retain every supplied name and tuple. */
export function renderInstanceGraph(stateData) {
  listenForResize();
  const prefix = `instance-graph-${++renderSequence}`;
  const root = html('section', 'instance-graph');
  root.setAttribute('aria-label', 'Instance visualization');
  const intro = html('div', 'instance-graph-intro');
  intro.append(html('h4', null, `Instance diagram · State ${(stateData.index ?? 0) + 1}`), html('p', null,
    'Follow labeled arrows through the objects. Select an object or connection to explore its exact details.'));
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
    option.value = String(index); select.append(option);
  });
  toolbar.append(label, select);
  root.append(toolbar);
  const controls = html('div', 'instance-graph-controls');
  controls.setAttribute('aria-label', 'Diagram controls');
  const fit = html('button', 'instance-graph-fit', 'Fit width');
  const normal = html('button', 'instance-graph-size-normal', '100%');
  const smaller = html('button', 'instance-graph-zoom-out', '−');
  const larger = html('button', 'instance-graph-zoom-in', '+');
  const zoomValue = html('output', 'instance-graph-zoom-value', '100%');
  const reset = html('button', 'instance-graph-reset-context', 'Show all connections');
  smaller.setAttribute('aria-label', 'Zoom out'); larger.setAttribute('aria-label', 'Zoom in');
  for (const button of [fit, normal, smaller, larger, reset]) button.type = 'button';
  controls.append(fit, normal, smaller, zoomValue, larger, reset);
  root.append(controls);
  const picture = html('div', 'instance-graph-picture'); root.append(picture);
  const relationKey = html('ul', 'instance-graph-relation-key');
  relationKey.setAttribute('aria-label', 'Relation names and tuple counts');
  stateData.relations.forEach((relation, index) => {
    const item = html('li'); item.setAttribute('data-relation-index', String(index));
    const swatch = html('span', 'instance-graph-relation-swatch');
    swatch.style.backgroundColor = COLORS[index % COLORS.length]; swatch.setAttribute('aria-hidden', 'true');
    const button = html('button', 'instance-graph-relation-button', `${relation.label} · ${relation.tuples.length} tuples`);
    button.type = 'button'; button.dataset.relationIndex = String(index);
    button.setAttribute('aria-pressed', 'false');
    button.setAttribute('aria-label', `Highlight relation ${relation.label}, ${relation.tuples.length} tuples.`);
    item.append(swatch, button);
    relationKey.append(item);
  });
  if (stateData.relations.length) root.append(relationKey);
  const help = html('details', 'instance-graph-help');
  help.append(html('summary', null, 'How to read this diagram'));
  const legend = html('ul', 'instance-graph-legend');
  legend.append(html('li', null, 'Solid boxes are individual objects or values. Their type appears below the name; select a box for every membership and its connected relations. Numeric values are labeled with a supplied type or “Value”.'),
    html('li', null, 'A labeled arrow is a two-column tuple: the first object points to the second. A loop returns to the same object.'),
    html('li', null, 'A dashed box is one tuple with more or fewer than two columns. Its numbered connections preserve the column order, including repeated objects.'));
  help.append(legend, html('p', 'instance-graph-layout-note',
    'The layout follows connections from top to bottom. Cycles stay grouped together and disconnected objects stay visible. Position does not add a relationship. Full names and all tuples remain in the exact data below.'));
  root.append(help);
  const initialDetails = 'Select an object or connection to read its exact details here.';
  const details = html('p', 'instance-graph-details', initialDetails);
  details.setAttribute('aria-live', 'polite'); details.setAttribute('aria-atomic', 'true'); root.append(details);
  let scale = 1, currentScroll, currentCanvas, currentRelationSelection;
  relationKey.addEventListener('click', event => {
    const button = event.target.closest('button.instance-graph-relation-button');
    if (button && relationKey.contains(button)) currentRelationSelection?.(Number(button.dataset.relationIndex), button);
  });
  function setScale(next) {
    scale = Math.max(0.25, Math.min(1.5, next));
    zoomValue.value = `${Math.round(scale * 100)}%`; zoomValue.textContent = zoomValue.value;
    smaller.disabled = scale <= 0.25; larger.disabled = scale >= 1.5;
    if (currentCanvas) {
      const bounds = currentCanvas.viewBox.baseVal;
      currentCanvas.style.width = `${bounds.width * scale}px`;
      currentCanvas.style.height = `${bounds.height * scale}px`;
      requestAnimationFrame(() => centerDiagram(currentScroll));
    }
  }
  fit.addEventListener('click', () => {
    if (currentScroll?.clientWidth && currentCanvas) setScale((currentScroll.clientWidth - 8) / currentCanvas.viewBox.baseVal.width);
  });
  normal.addEventListener('click', () => setScale(1));
  smaller.addEventListener('click', () => setScale(scale - 0.25));
  larger.addEventListener('click', () => setScale(scale + 0.25));
  reset.addEventListener('click', () => {
    for (const item of currentCanvas?.querySelectorAll('.instance-graph-node, .instance-graph-tuple') || []) {
      item.classList.remove('instance-graph-context-related', 'instance-graph-context-muted');
      item.setAttribute('aria-pressed', 'false');
    }
    for (const item of root.querySelectorAll('[aria-pressed="true"]')) item.setAttribute('aria-pressed', 'false');
    for (const item of relationKey.children) item.classList.remove('instance-graph-relation-focused');
    details.textContent = initialDetails;
  });
  function refresh() {
    const graph = buildInstanceGraph(stateData, { relationIndex: select.value === 'all' ? null : Number(select.value) });
    const content = []; currentScroll = undefined; currentCanvas = undefined; currentRelationSelection = undefined;
    if (graph.limited) content.push(html('p', 'instance-graph-limit',
      `Limited picture: showing ${graph.counts.shownNodes} of ${graph.counts.nodes} atoms/values and `
      + `${graph.counts.shownTuples} of ${graph.counts.tuples} tuples for the selected relations. The exact data tables retain all supplied data.`));
    if (!graph.nodes.length) content.push(html('p', 'instance-graph-empty', 'This state has no atoms or relation values to draw.'));
    else {
      if (!graph.tuples.length) content.push(html('p', 'instance-graph-empty',
        'There are no tuples in the selected relations. The boxes show the atoms and values in this state.'));
      currentScroll = html('div', 'instance-graph-scroll');
      currentScroll.setAttribute('tabindex', '0'); currentScroll.setAttribute('role', 'region');
      currentScroll.setAttribute('aria-label', 'Instance diagram; scroll to explore when needed');
      const drawn = draw(graph, prefix, details);
      currentCanvas = drawn.canvas; currentRelationSelection = drawn.selectRelation; currentScroll.append(currentCanvas);
      content.push(html('p', 'instance-graph-scroll-hint', 'Scroll to follow the hierarchy, or use Fit width for an overview.'), currentScroll);
    }
    const emptySignatures = graph.signatures.filter(signature => !signature.count).map(signature => signature.label);
    if (emptySignatures.length) content.push(html('p', 'instance-graph-empty', `Signatures with no atoms: ${emptySignatures.join(', ')}.`));
    picture.replaceChildren(...content); details.textContent = initialDetails;
    for (const item of relationKey.children) {
      item.classList.remove('instance-graph-relation-focused');
      item.querySelector('button').setAttribute('aria-pressed', 'false');
      item.querySelector('button').disabled = !currentCanvas;
      item.classList.toggle('instance-graph-relation-selected',
        select.value !== 'all' && item.getAttribute('data-relation-index') === select.value);
    }
    for (const button of [fit, normal, smaller, larger, reset]) button.disabled = !currentCanvas;
    if (currentCanvas) setScale(scale);
  }
  select.addEventListener('change', refresh); refresh(); return root;
}
