// 注入的 polyfill（performance / TextEncoder / TextDecoder）与桥原语可见性。

test('performance-now', () => {
  assert.eq('number', typeof performance.now());
  const a = performance.now();
  let s = 0;
  for (let i = 0; i < 50000; i++) s += i;
  assert.ok(performance.now() >= a, 'now must be monotonic');
});

test('performance-time-origin', () => {
  assert.eq('number', typeof performance.timeOrigin);
  assert.ok(performance.timeOrigin > 0, 'timeOrigin > 0');
});

test('text-encoder-decoder', () => {
  assert.eq('utf-8', new TextEncoder().encoding);
  assert.eq('utf-8', new TextDecoder().encoding);
  assert.eq('[104,105]', JSON.stringify(Array.from(new TextEncoder().encode('hi'))));
  assert.eq('中😀', new TextDecoder().decode(new TextEncoder().encode('中😀')));
  assert.eq('A\uFFFDB', new TextDecoder().decode(new Uint8Array([65, 255, 66])));
  assert.throws(() => new TextDecoder('gbk'));
});

test('bridge-globals', () => {
  // 桥在上下文创建时注入的原语（运行时的 $dev/$binary/__plugin 由宿主注入，桥层不存在）
  assert.eq('function', typeof __yeowWrite);
  assert.eq('function', typeof __yeowRead);
  assert.eq('undefined', typeof __yeowUtf8Encode, 'internal primitive hidden');
});
