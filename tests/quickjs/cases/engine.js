// 引擎语义语料。通过 Runner 注入的 test()/testAsync()/assert 编写。
// 每个文件在一个全新的 QuickJS 上下文中执行。

test('arithmetic-and-numbers', () => {
  assert.eq(6, 2 * 3);
  assert.eq(0.5, 1 / 2);
  assert.eq(1024, 2 ** 10);
  assert.ok(Number.isInteger(42));
  assert.ok(!Number.isInteger(1.5));
});

test('string-non-bmp', () => {
  assert.eq(2, '😀'.length);            // UTF-16 代理对
  assert.eq(1, Array.from('😀').length); // 码点
  assert.eq('中😀', '中' + '😀');
});

test('objects-and-arrays', () => {
  assert.eq([2, 4, 6], [1, 2, 3].map((x) => x * 2));
  assert.eq({ a: 1, b: 2 }, { a: 1, b: 2 });
  assert.eq(3, Object.keys({ a: 1, b: 2, c: 3 }).length);
  assert.eq(6, [1, 2, 3].reduce((a, b) => a + b, 0));
});

test('closures-and-scope', () => {
  let n = 0;
  const inc = () => ++n;
  inc();
  inc();
  assert.eq(2, n);
  assert.eq(3, inc());
});

test('errors', () => {
  let msg = '';
  try {
    throw new Error('boom');
  } catch (e) {
    msg = e.message;
  }
  assert.eq('boom', msg);
  assert.throws(() => JSON.parse('{'));
  assert.eq('TypeError', (() => {
    try {
      null.x;
      return '';
    } catch (e) {
      return e.name;
    }
  })());
});

test('json', () => {
  assert.eq('{"a":1,"b":[1,2]}', JSON.stringify({ a: 1, b: [1, 2] }));
  assert.eq(2, JSON.parse('{"b":[1,2]}').b[1]);
});

testAsync('promise-chain', () => Promise.resolve(41).then((v) => assert.eq(42, v + 1)));

testAsync('promise-reject', () => Promise.reject(new Error('expected-reject')).then(
  () => { throw new Error('should not resolve'); },
  (e) => assert.eq('expected-reject', e.message),
));
