// The browser sends only learner draft identity and opaque evidence/history pins.
// All exercise content, structural retrieval and conversation history stay server-owned.
export function createTutorChat({ baseURL, getContext, ensureChannel, clearChannel = () => {} }) {
  const byId = id => document.getElementById(id);
  const ui = Object.fromEntries(['toggle', 'panel', 'close', 'new', 'model', 'effort', 'availability',
    'exercise', 'context-badges', 'context-note', 'messages', 'suggest', 'suggestions',
    'suggestion-note', 'form', 'message', 'message-limit', 'history-note', 'send', 'status',
    'history', 'history-panel', 'history-close', 'history-select', 'history-content', 'history-resume', 'archive-note'].map(id => [id, byId(`tutor-${id}`)]));
  if (!ui.panel) return { refresh() {} };
  let open = false, models = [], loaded = false, contextKey = null, conversationKey = null, conversationToken = null, conversationChannel = null;
  let epoch = 0, request = null, suggestionsRequest = null, modelRequest = null;
  let suggestedKey = null;
  let starterNotice = '', starterContext = null, archives = [], archiveNumber = 0, archiveOpen = false, pendingResume = false;
  const preferenceKey = 'alloy-studio:v1:tutor-profile';
  const archiveLimit = 64 * 1024;
  const byteLength = value => new TextEncoder().encode(value).length;
  let followTail = true, messageViewportHeight = ui.messages.clientHeight, messageViewportWidth = ui.messages.clientWidth;
  // Follow a reader already at the end when surrounding controls resize the log.
  // A reader inspecting older messages keeps their scroll position instead.
  ui.messages.addEventListener('scroll', () => {
    const height = ui.messages.clientHeight;
    if (!height || height !== messageViewportHeight || ui.messages.clientWidth !== messageViewportWidth) return;
    followTail = ui.messages.scrollHeight - height - ui.messages.scrollTop <= 24;
  });
  ui.messages.addEventListener('wheel', event => { if (event.deltaY < 0) followTail = false; }, { passive: true });
  const messageResize = new ResizeObserver(() => {
    const height = ui.messages.clientHeight;
    if (!height) return;
    messageViewportHeight = height;
    messageViewportWidth = ui.messages.clientWidth;
    if (followTail && open && !archiveOpen) ui.messages.scrollTop = ui.messages.scrollHeight;
  });
  messageResize.observe(ui.messages);
  const messageContexts = new WeakMap();
  const element = (tag, className, text) => {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text !== undefined) item.textContent = text;
    return item;
  };
  const keyOf = context => context ? JSON.stringify([context.payload.exerciseId, context.payload.body,
    context.payload.revision, context.payload.metric, context.payload.prerequisiteTokens,
    context.contentVersion, context.selection]) : null;
  const conversationKeyOf = context => context ? JSON.stringify([context.payload.exerciseId,
    context.contentVersion, context.payload.prerequisiteTokens]) : null;
  const selected = () => models.find(model => model.id === ui.model.value);
  const profileKey = () => `${ui.model.value}:${ui.effort.value}`;
  const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
  const mobile = matchMedia('(max-width: 730px)');
  const background = [...document.querySelectorAll('.app-header, .app-shell')];
  function accessibility() {
    const modal = open && mobile.matches;
    for (const area of background) area.inert = modal;
    if (modal) { ui.panel.setAttribute('role', 'dialog'); ui.panel.setAttribute('aria-modal', 'true'); }
    else { ui.panel.removeAttribute('role'); ui.panel.removeAttribute('aria-modal'); }
  }
  mobile.addEventListener('change', accessibility);
  const token = value => typeof value === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value);
  const stop = () => {
    epoch += 1;
    request?.abort(); suggestionsRequest?.abort();
    request = null; suggestionsRequest = null;
    ui.messages.removeAttribute('aria-busy');
  };
  const messageBytes = () => new TextEncoder().encode(ui.message.value.trim()).length;
  function modelHelp() {
    ui.availability.textContent = 'Higher reasoning effort may take longer. Switching keeps your conversation and unsent message.';
  }
  function readPreference() {
    try {
      const saved = JSON.parse(localStorage.getItem(preferenceKey));
      const model = saved && models.find(item => item.id === saved.model);
      return model && Object.keys(saved).length === 2 && model.efforts.includes(saved.effort) ? saved : null;
    } catch { return null; }
  }
  function savePreference() {
    try { localStorage.setItem(preferenceKey, JSON.stringify({ model: ui.model.value, effort: ui.effort.value })); }
    catch { /* Optional preference storage must not block tutoring. */ }
  }
  function showStarters(context, detail = '') {
    if (!context) return;
    const identity = keyOf(context);
    starterContext = JSON.stringify([identity, !!context.hasHints, !!context.hasExamples]);
    const questions = ['Help me understand what this question is asking.',
      context.hasHints ? 'Help me interpret the first repair hint.' : 'Help me reason about my current approach.',
      context.hasExamples ? 'Help me read one counterexample.' : 'What small example could I reason through?'];
    ui.suggestions.replaceChildren();
    for (const question of questions) {
      const button = element('button', 'tutor-suggestion', question); button.type = 'button'; button.dataset.starter = 'true';
      button.addEventListener('click', () => {
        if (!open || keyOf(getContext()) !== identity || ui.message.value !== '' || request) return;
        ui.message.value = question; controls(); ui.message.focus();
      });
      ui.suggestions.append(button);
    }
    starterNotice = 'Starting points — edit a question, then send it.' + (detail ? ` ${detail}` : '');
  }
  function historyView(show, focus = true) {
    archiveOpen = show && archives.length > 0;
    ui.panel.classList.toggle('tutor-viewing-history', archiveOpen);
    ui['history-panel'].hidden = !archiveOpen;
    ui.history.setAttribute('aria-expanded', String(archiveOpen));
    if (archiveOpen) renderArchive();
    if (focus) (archiveOpen ? ui['history-select'] : ui.history).focus();
  }
  function renderArchive() {
    const archive = archives.find(item => String(item.number) === ui['history-select'].value) || archives.at(-1);
    ui['history-content'].replaceChildren();
    if (!archive) return;
    ui['history-select'].value = String(archive.number);
    ui['archive-note'].textContent = 'History for this exercise stays in this tab until reload. Up to five chats and 64 KiB of text are retained. Viewing makes no AI request. '
      + 'Resuming uses only server-retained history (up to six exchanges for 30 minutes). '
      + (archive.truncated ? 'Older messages from this chat were omitted to fit the limit. ' : '')
      + (ui.message.value !== '' ? 'Your current message is kept; copy text here or return to finish it.' : 'Use a past question to start a new draft; it is never sent automatically.');
    for (const message of archive.messages) {
      const item = element('article', `tutor-bubble tutor-${message.role}`);
      item.append(element('strong', 'tutor-speaker', message.speaker), element('p', '', message.value));
      if (message.role === 'user') {
        const use = element('button', 'tutor-history-use', 'Use this question'); use.type = 'button';
        use.disabled = ui.message.value !== '';
        use.addEventListener('click', () => {
          if (!archiveOpen || ui.message.value !== '' || !archives.includes(archive)) return;
          ui.message.value = message.value; historyView(false, false); controls(); ui.message.focus();
        });
        item.append(use);
      }
      ui['history-content'].append(item);
    }
  }
  function updateArchiveControls() {
    ui.history.disabled = !archives.length;
    ui.history.hidden = !archives.length;
    ui.history.textContent = archives.length ? `History (${archives.length})` : 'History';
    ui['history-select'].replaceChildren(...archives.map(item => new Option(`Chat ${item.number} · ${item.title}`, String(item.number))));
    if (archives.length) ui['history-select'].value = String(archives.at(-1).number);
  }
  function archiveConversation() {
    const messages = [...ui.messages.querySelectorAll('.tutor-bubble')].map(item => {
      const speaker = item.querySelector('.tutor-speaker')?.cloneNode(true);
      speaker?.querySelectorAll('.tutor-draft-label').forEach(label => label.remove());
      return { role: item.classList.contains('tutor-user') ? 'user' : 'assistant',
        speaker: speaker?.textContent || '', value: item.querySelector('p')?.textContent || '' };
    });
    if (!messages.length) return;
    const size = message => byteLength(message.speaker) + byteLength(message.value);
    let bytes = messages.reduce((total, item) => total + size(item), 0), truncated = false;
    while (bytes > archiveLimit && messages.length) { bytes -= size(messages.shift()); truncated = true; }
    if (!messages.length) return;
    const title = (messages.find(item => item.role === 'user')?.value || 'Tutor conversation').slice(0, 70);
    bytes += byteLength(title);
    while (bytes > archiveLimit && messages.length) { bytes -= size(messages.shift()); truncated = true; }
    if (!messages.length) return;
    if (archives.some(item => item.token === conversationToken && item.channel === conversationChannel
      && JSON.stringify(item.messages) === JSON.stringify(messages))) return;
    archives.push({ number: ++archiveNumber, title, messages, bytes, truncated, token: conversationToken, channel: conversationChannel });
    while (archives.length > 5 || archives.reduce((total, item) => total + item.bytes, 0) > archiveLimit) archives.shift();
    updateArchiveControls();
  }
  function resumeArchive() {
    const archive = archives.find(item => String(item.number) === ui['history-select'].value);
    if (!archiveOpen || !archive) return;
    const draft = ui.message.value;
    archiveConversation(); historyView(false, false);
    reset('Continuing an earlier conversation.');
    conversationToken = archive.token; conversationChannel = archive.channel;
    pendingResume = !!conversationToken;
    for (const message of archive.messages) {
      addMessage(message.role, message.value);
      const item = ui.messages.lastElementChild;
      item.classList.add('tutor-earlier');
      item.querySelector('.tutor-speaker').textContent = message.speaker;
      item.querySelector('.tutor-speaker').append(element('span', 'tutor-draft-label', 'Earlier conversation'));
      messageContexts.set(item, 'archived');
    }
    ui.message.value = draft; refresh();
    ui.status.textContent = pendingResume
      ? 'Earlier conversation selected. Your next message will send an abridged history with your current draft.'
      : 'This chat has no saved server history. Your next message starts a fresh conversation; the earlier text stays readable here.';
    ui.message.focus();
  }
  function controls() {
    const ready = open && !!getContext() && !!selected();
    ui.model.disabled = !models.length || !!request;
    ui.effort.disabled = !selected() || selected().efforts.length < 2 || !!request;
    ui.message.disabled = !ready;
    const tooLong = messageBytes() > 2048;
    ui['message-limit'].hidden = !tooLong;
    ui['message-limit'].textContent = tooLong ? 'Keep your message within 2 KiB (2,048 UTF-8 bytes). Shorten it before sending.' : '';
    ui.send.disabled = !ready || !!request || !!suggestionsRequest || !ui.message.value.trim() || tooLong;
    ui.suggest.disabled = !ready || !!request || !!suggestionsRequest;
    for (const button of ui.suggestions.querySelectorAll('button')) button.disabled = !ready || !!request
      || (button.dataset.starter ? ui.message.value !== '' : !!suggestionsRequest);
    if (ui.suggestions.querySelector('[data-starter]')) ui['suggestion-note'].textContent = starterNotice
      + (ui.message.value !== '' ? ' Your draft is kept; clear the message box to choose a starting point.' : '');
  }
  function empty(message = 'What would you like to understand?') {
    followTail = true;
    ui.messages.replaceChildren(element('p', 'tutor-empty', message));
  }
  function reset(message) {
    stop(); conversationToken = null; pendingResume = false; suggestedKey = null;
    ui.suggestions.replaceChildren(); ui.message.value = ''; ui.status.textContent = '';
    ui['suggestion-note'].textContent = "Suggestions use the lowest-cost configured model in this provider's stack.";
    ui['history-note'].hidden = true; ui['history-note'].textContent = '';
    empty(message);
  }
  function refresh() {
    const context = getContext(), nextKey = keyOf(context), nextConversation = conversationKeyOf(context);
    if (nextConversation !== conversationKey) {
      const hadConversation = conversationKey !== null;
      conversationKey = nextConversation; contextKey = nextKey;
      archives = []; archiveNumber = 0; historyView(false, false); updateArchiveControls();
      ui['history-content'].replaceChildren(); ui['archive-note'].textContent = '';
      reset(hadConversation ? 'The exercise or its prerequisites changed. Start a fresh conversation here.'
        : 'What would you like to understand?');
    } else if (nextKey !== contextKey) {
      contextKey = nextKey;
      // Draft revisions update retrieval, but preserve this exercise's server-owned
      // conversation. Old provider responses and suggestion callbacks stay stale.
      stop(); suggestedKey = null; ui.suggestions.replaceChildren();
      ui['suggestion-note'].textContent = 'Your draft changed. Ask for new suggestions when ready.';
      for (const item of ui.messages.querySelectorAll('.tutor-bubble')) {
        if (messageContexts.get(item) !== contextKey) {
          item.classList.add('tutor-earlier');
          if (!item.querySelector('.tutor-draft-label')) item.querySelector('.tutor-speaker')
            .append(element('span', 'tutor-draft-label', 'Earlier draft'));
        }
      }
      ui.status.textContent = ui.messages.querySelector('.tutor-bubble')
        ? 'Your conversation is kept. The next answer will use your current draft and its available checks.' : '';
    }
    ui.exercise.textContent = context?.title || 'Choose an unlocked exercise';
    ui['context-badges'].replaceChildren();
    for (const [label, available] of [['Question', !!context], ['Your code', !!context],
      ['Hints + repair DSL', !!context?.hasHints], ['Examples', !!context?.hasExamples]]) {
      const badge = element('li', available ? 'available' : '', `${available ? '✓' : '○'} ${label}`);
      ui['context-badges'].append(badge);
    }
    ui['context-note'].textContent = !context ? 'Select an unlocked exercise to begin.'
      : context.hasHints && context.hasExamples ? 'Using the checked hints and examples for this exact draft.'
        : 'Check this draft to share its available hints and bounded examples. You can ask about the question and code now.';
    if (open && context && !request && (!ui.suggestions.children.length
      || ui.suggestions.querySelector('[data-starter]') && starterContext !== JSON.stringify([nextKey, !!context.hasHints, !!context.hasExamples]))) showStarters(context);
    controls();
  }
  function responseMatches(result, context) {
    return result.exerciseId === context.payload.exerciseId && result.revision === context.payload.revision
      && result.metric === context.payload.metric && result.contentVersion === context.contentVersion;
  }
  async function json(path, options) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) controller.abort();
    let timedOut = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 120000);
    try {
      const response = await fetch(new URL(path, baseURL), { cache: 'no-store', ...options, signal: controller.signal });
      if (response.redirected || !/^application\/json(?:;|$)/i.test(response.headers.get('content-type') || '')) {
        throw new Error('The tutor service did not return a readable response. Please try again.');
      }
      const result = await response.json();
      if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('The tutor response was not readable.');
      if (!response.ok || result.status !== 'ok') {
        const problem = new Error(text(result.message, 500) ? result.message
          : text(result.error, 500) ? result.error
            : result.code === 'channel_expired' ? 'Your editing session expired. Send your question again; check the draft again to refresh its hints and examples.'
              : result.status === 'disabled' ? 'The administrator has not enabled AI chat for this deployment.'
                : 'The tutor is temporarily unavailable. Please try again.');
        problem.code = result.code; problem.status = result.status;
        throw problem;
      }
      return result;
    } catch (error) {
      if (timedOut) throw new Error('The tutor took too long to respond. Please try again.');
      throw error;
    } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort); }
  }

  function renderEfforts(preferred) {
    const model = selected(); ui.effort.replaceChildren();
    for (const effort of model?.efforts || []) ui.effort.append(new Option(effort === 'none' ? 'No reasoning' : effort[0].toUpperCase() + effort.slice(1), effort));
    if (model) ui.effort.value = model.efforts.includes(preferred) ? preferred : model.defaultEffort;
    controls();
  }
  async function loadModels() {
    if (loaded || modelRequest) return;
    const controller = new AbortController(); modelRequest = controller;
    ui.availability.textContent = 'Finding configured models…';
    try {
      const result = await json('api/chat/models', { signal: controller.signal });
      if (!open || modelRequest !== controller) return;
      const entries = Array.isArray(result.models) ? result.models : [];
      const ids = new Set();
      models = entries.filter(model => model && text(model.id, 160) && !ids.has(model.id) && ids.add(model.id)
        && text(model.label, 120) && text(model.provider, 40) && Array.isArray(model.efforts)
        && model.efforts.length > 0 && model.efforts.length <= 12 && model.efforts.every(effort => text(effort, 30))
        && model.efforts.includes(model.defaultEffort)).slice(0, 40);
      if (!models.length) throw new Error('No chat models are configured. Ask the administrator to configure a provider.');
      ui.model.replaceChildren();
      const groups = new Map();
      for (const model of models) {
        if (!groups.has(model.provider)) {
          const group = element('optgroup'); group.label = { openai: 'OpenAI', anthropic: 'Claude', gemini: 'Gemini' }[model.provider] || model.provider;
          groups.set(model.provider, group); ui.model.append(group);
        }
        groups.get(model.provider).append(new Option(model.label, model.id));
      }
      const luna = models.find(model => /luna/i.test(model.id));
      const saved = readPreference();
      ui.model.value = saved?.model || (models.some(model => model.id === result.defaultModel) ? result.defaultModel : (luna || models[0]).id);
      renderEfforts(saved?.effort || (/luna/i.test(ui.model.value) ? 'low' : undefined));
      loaded = true;
      modelHelp();
      refresh();
      await suggest();
    } catch (error) {
      if (error.name !== 'AbortError' && open) {
        ui.availability.textContent = error.message;
        ui.model.replaceChildren(new Option('Chat unavailable', ''));
      }
    } finally { if (modelRequest === controller) modelRequest = null; controls(); }
  }
  async function payloadFor(context, signal, pendingMessage = null) {
    const { channel } = await ensureChannel(signal);
    if (!token(channel)) throw new Error('A live editing session is required. Try again shortly.');
    // Another editor request may have renewed an expired channel. Its capability
    // cannot own the previous session's chat token, even when the draft is unchanged.
    if (conversationChannel && conversationChannel !== channel) {
      conversationToken = null; pendingResume = false; suggestedKey = null; ui.suggestions.replaceChildren();
      ui['history-note'].hidden = true; ui['history-note'].textContent = '';
      empty('Your editing session changed. This starts a new conversation about the current draft.');
      if (pendingMessage) addMessage('user', pendingMessage);
    }
    conversationChannel = channel;
    return { ...context.payload, channel, model: ui.model.value, effort: ui.effort.value,
      ...(conversationToken && (!pendingResume || pendingMessage !== null) ? { conversationToken } : {}),
      ...(pendingResume && pendingMessage !== null ? { resumeConversation: true } : {}) };
  }
  function addMessage(role, value, model = null, effort = null) {
    ui.messages.querySelector('.tutor-empty')?.remove();
    const item = element('article', `tutor-bubble tutor-${role}`);
    const speaker = role === 'user' ? 'You' : model ? `Tutor · ${model} · ${effort}` : 'Tutor';
    item.append(element('strong', 'tutor-speaker', speaker), element('p', '', value));
    messageContexts.set(item, contextKey);
    ui.messages.append(item);
    // Bound visual history as well as server-side history; avoid an unbounded tab.
    while (ui.messages.children.length > 40) ui.messages.firstElementChild.remove();
    if (followTail) ui.messages.scrollTop = ui.messages.scrollHeight;
  }
  async function suggest() {
    refresh();
    const context = getContext(), model = selected();
    if (!open || !context || !model || request || suggestionsRequest) return;
    const identity = keyOf(context), profile = profileKey(), currentEpoch = epoch;
    const controller = new AbortController(); suggestionsRequest = controller;
    // A fold/reopen does not retry a paid call, even if its response was lost.
    // A fresh draft/reply or an explicit click can request a new suggestion set.
    suggestedKey = `${identity}:${profile}`;
    let sentChannel = null;
    const current = () => open && epoch === currentEpoch && keyOf(getContext()) === identity
      && profileKey() === profile && !controller.signal.aborted;
    showStarters(context, 'Preparing tailored questions…'); controls();
    try {
      const payload = await payloadFor(context, controller.signal);
      sentChannel = payload.channel;
      if (!current()) return;
      suggestedKey = `${identity}:${profile}`;
      const result = await json('api/chat/suggestions', { method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
      if (!current()) return;
      if (!responseMatches(result, context) || !Array.isArray(result.suggestions)
        || result.suggestions.some(value => !text(value, 400))) throw new Error('Suggested questions could not be matched to this draft.');
      ui.suggestions.replaceChildren();
      for (const value of [...new Set(result.suggestions)].slice(0, 3)) {
        const button = element('button', 'tutor-suggestion', value); button.type = 'button';
        button.addEventListener('click', () => {
          if (open && keyOf(getContext()) === identity && profileKey() === profile && !request) {
            send(value);
          }
        });
        ui.suggestions.append(button);
      }
      suggestedKey = `${identity}:${profile}`;
      ui['suggestion-note'].textContent = result.suggestions.length
        ? 'Choose a question, or write your own. Suggestions use a lower-cost model.'
        : 'No suggestions this time. You can write your own question.';
      if (!result.suggestions.length) showStarters(context, 'No tailored questions this time.');
    } catch (error) {
      if (current() && error.name !== 'AbortError') {
        const draft = ui.message.value;
        if (error.code === 'channel_expired') { clearChannel(sentChannel); reset('The editing session expired. Start a new conversation about this draft.'); refresh(); ui.message.value = draft; }
        showStarters(getContext(), error.message);
      }
    } finally { if (suggestionsRequest === controller) suggestionsRequest = null; controls(); }
  }
  async function send(suggestedMessage = null) {
    refresh();
    const fromSuggestion = typeof suggestedMessage === 'string';
    const message = (fromSuggestion ? suggestedMessage : ui.message.value).trim(), context = getContext();
    if (!open || !context || !selected() || request || suggestionsRequest || !text(message, 2048) || byteLength(message) > 2048) { controls(); return; }
    // Sending waits for the bounded suggestion request to settle. Aborting a
    // browser fetch does not release the provider's active channel immediately.
    const identity = keyOf(context), profile = profileKey(), currentEpoch = epoch;
    const controller = new AbortController(); request = controller;
    const current = () => open && epoch === currentEpoch && keyOf(getContext()) === identity
      && profileKey() === profile && !controller.signal.aborted;
    ui.suggestions.replaceChildren(); if (!fromSuggestion) ui.message.value = ''; addMessage('user', message);
    ui.status.textContent = 'The tutor is thinking…'; ui.messages.setAttribute('aria-busy', 'true'); controls();
    let succeeded = false, sentChannel = null;
    try {
      const payload = await payloadFor(context, controller.signal, message);
      sentChannel = payload.channel;
      if (!current()) return;
      const result = await json('api/chat', { method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...payload, message }) });
      if (!current()) return;
      if (!responseMatches(result, context) || result.model !== payload.model || result.effort !== payload.effort
        || !text(result.reply, 12000) || !token(result.conversationToken)) throw new Error('The tutor reply could not be matched to this draft. Please try again.');
      conversationToken = result.conversationToken;
      pendingResume = false;
      addMessage('assistant', result.reply, models.find(model => model.id === result.model)?.label || result.model, result.effort);
      ui['history-note'].hidden = true; ui['history-note'].textContent = '';
      if (result.historyTruncated === true || result.historyCompressed === true) {
        ui['history-note'].textContent = (result.historyCompressed === true
          ? 'Earlier discussion was abridged for this reply, using your current draft and available checks. ' : '')
          + (result.historyTruncated === true ? 'Earlier messages are no longer included. The tutor remembers the latest six exchanges in this chat.' : '');
        ui['history-note'].hidden = false;
      }
      ui.status.textContent = ''; succeeded = true;
    } catch (error) {
      if (current() && error.name !== 'AbortError') {
        // Capture the next draft before session recovery clears the composer.
        // Keep the failed question visible, but never send it automatically.
        const draft = ui.message.value;
        if (error.code === 'channel_expired') {
          clearChannel(sentChannel); reset('The editing session expired. Start a new conversation about this draft.'); refresh();
          addMessage('user', message);
        }
        ui.message.value = draft || message;
        ui.status.textContent = error.message + (draft
          ? ' Your unanswered question is kept above. Copy it to the message box to try it again.' : '');
      }
    } finally {
      if (request === controller) { request = null; ui.messages.removeAttribute('aria-busy'); }
      controls();
    }
    if (succeeded && current()) await suggest();
  }
  function close() {
    if (suggestionsRequest) showStarters(getContext(), 'Suggestion request interrupted. Choose Suggest questions to try again.');
    open = false; stop(); modelRequest?.abort(); modelRequest = null; accessibility();
    ui.panel.hidden = true; ui.toggle.hidden = false; ui.toggle.setAttribute('aria-expanded', 'false');
    document.body.classList.remove('tutor-open'); ui.status.textContent = ''; ui.toggle.focus();
  }
  ui.toggle.addEventListener('click', async () => {
    open = true; ui.panel.hidden = false; ui.toggle.hidden = true;
    ui.toggle.setAttribute('aria-expanded', 'true'); document.body.classList.add('tutor-open');
    refresh(); accessibility(); ui.close.focus();
    if (!loaded) await loadModels();
    else if (!pendingResume && getContext() && suggestedKey !== `${keyOf(getContext())}:${profileKey()}`) await suggest();
  });
  ui.close.addEventListener('click', close);
  ui.new.addEventListener('click', () => {
    const draft = ui.message.value; archiveConversation(); historyView(false, false);
    reset('A fresh conversation about your current draft.'); refresh(); ui.message.value = draft; controls();
    if (!ui.message.disabled) ui.message.focus();
  });
  ui.history.addEventListener('click', () => historyView(true));
  ui['history-close'].addEventListener('click', () => historyView(false));
  ui['history-select'].addEventListener('change', renderArchive);
  ui['history-resume'].addEventListener('click', resumeArchive);
  ui.panel.addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); close(); }
    if (event.key === 'Tab' && mobile.matches) {
      const focusable = [...ui.panel.querySelectorAll('button:not(:disabled), select:not(:disabled), textarea:not(:disabled)')]
        .filter(item => !item.hidden && item.getClientRects().length);
      const first = focusable[0], last = focusable.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  });
  function changeProfile(modelChanged) {
    // Keep the history capability and message. An older suggestion still owns its
    // backend slot until completion, so let it settle but ignore its result.
    epoch += 1;
    if (modelChanged) renderEfforts(ui.effort.value);
    suggestedKey = null; ui.suggestions.replaceChildren();
    showStarters(getContext(), 'Choose Suggest questions for your selected model.');
    savePreference(); modelHelp();
    ui.status.textContent = `Conversation kept. Your next reply will use ${selected()?.label || 'the selected model'} (${ui.effort.value}).`;
    refresh();
  }
  ui.model.addEventListener('change', () => changeProfile(true));
  ui.effort.addEventListener('change', () => changeProfile(false));
  ui.form.addEventListener('submit', event => { event.preventDefault(); send(); });
  ui.message.addEventListener('input', controls);
  ui.message.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); send(); }
  });
  ui.suggest.addEventListener('click', suggest);
  return { refresh };
}
