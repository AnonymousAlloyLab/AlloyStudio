// Browser-measured geometry only: these checks deliberately do not call the
// renderer's layout or routing helpers, so the test cannot repeat their bugs.
export function instanceDiagramGeometry(canvas) {
  const failures = [];
  const objects = [...canvas.querySelectorAll('.instance-graph-node > rect, .instance-graph-junction > rect')]
    .map(element => ({ element, bounds: element.getBoundingClientRect(),
      id: element.closest('[data-atom]')?.dataset.atom || element.closest('[data-tuple-id]')?.dataset.tupleId }));
  const texts = [...canvas.querySelectorAll('text')]
    .map(element => ({ element, bounds: element.getBoundingClientRect(), text: element.textContent,
      tuple: element.closest('[data-tuple-id]')?.dataset.tupleId,
      object: element.closest('.instance-graph-node, .instance-graph-junction') }));
  const intersects = (a, b, inset = 0.5) => a.left < b.right - inset && a.right > b.left + inset
    && a.top < b.bottom - inset && a.bottom > b.top + inset;
  const contains = (outer, inner, tolerance = 0.5) => inner.left >= outer.left - tolerance
    && inner.right <= outer.right + tolerance && inner.top >= outer.top - tolerance
    && inner.bottom <= outer.bottom + tolerance;
  const fail = (kind, data) => failures.push({ kind, ...data });
  for (let first = 0; first < objects.length; first += 1) {
    for (let second = first + 1; second < objects.length; second += 1) {
      if (intersects(objects[first].bounds, objects[second].bounds)) {
        fail('objects-overlap', { first: objects[first].id, second: objects[second].id });
      }
    }
  }
  const canvasBounds = canvas.getBoundingClientRect();
  for (const [index, text] of texts.entries()) {
    if (text.bounds.width <= 0 || text.bounds.height <= 0) fail('text-invisible', { text: text.text });
    if (!contains(canvasBounds, text.bounds)) fail('text-clipped-by-canvas', { text: text.text });
    for (const other of texts.slice(index + 1)) {
      if (intersects(text.bounds, other.bounds)) fail('texts-overlap', { first: text.text, second: other.text });
    }
    for (const object of objects) {
      if (text.object === object.element.parentElement) {
        if (!contains(object.bounds, text.bounds)) fail('text-outside-own-object', { text: text.text, object: object.id });
      } else if (intersects(text.bounds, object.bounds)) {
        fail('text-overlaps-unrelated-object', { text: text.text, object: object.id });
      }
    }
  }
  for (const edge of canvas.querySelectorAll('.instance-graph-edge')) {
    const tuple = edge.closest('[data-tuple-id]')?.dataset.tupleId;
    const edgeBounds = edge.getBoundingClientRect();
    const obstacles = [
      ...texts.filter(text => text.tuple !== tuple).map(text => ({ bounds: text.bounds, kind: 'text', id: text.text })),
      ...objects.filter(object => {
        const atom = object.element.closest('[data-atom]')?.dataset.atom;
        if (atom !== undefined) return atom !== edge.dataset.source && atom !== edge.dataset.target;
        return object.element.closest('[data-tuple-id]')?.dataset.tupleId !== tuple;
      }).map(object => ({ bounds: object.bounds, kind: 'object', id: object.id })),
    ].filter(obstacle => intersects(edgeBounds, obstacle.bounds, -1));
    if (!obstacles.length) continue;
    const length = edge.getTotalLength();
    const samples = Math.max(1, Math.ceil(length / 2));
    const matrix = edge.getScreenCTM();
    const hits = new Set();
    for (let index = 1; index < samples; index += 1) {
      const point = edge.getPointAtLength(length * index / samples).matrixTransform(matrix);
      for (const obstacle of obstacles) {
        const bounds = obstacle.bounds;
        if (!hits.has(obstacle) && point.x > bounds.left + 0.5 && point.x < bounds.right - 0.5
          && point.y > bounds.top + 0.5 && point.y < bounds.bottom - 0.5) {
          fail(`edge-overlaps-unrelated-${obstacle.kind}`, { tuple, column: edge.dataset.column || null, obstacle: obstacle.id });
          hits.add(obstacle);
        }
      }
    }
  }
  return { failures, objects: objects.length, texts: texts.length,
    edges: canvas.querySelectorAll('.instance-graph-edge').length };
}
