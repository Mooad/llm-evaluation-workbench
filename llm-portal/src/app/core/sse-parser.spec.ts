import { SseParser } from './sse-parser';

describe('SseParser', () => {
  it('lit le format ecrit par Spring (sans espace apres les deux-points)', () => {
    const messages = new SseParser().push('event:delta\ndata:{"content":"Bon"}\n\n');

    expect(messages).toEqual([{ event: 'delta', data: '{"content":"Bon"}' }]);
  });

  it('reconstitue un message coupe entre deux fragments reseau', () => {
    const parser = new SseParser();

    expect(parser.push('event: delta\nda')).toEqual([]);
    expect(parser.push('ta: {"content":"jour"}\n')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ event: 'delta', data: '{"content":"jour"}' }]);
  });

  it('accepte les fins de ligne CRLF et ignore les commentaires keep-alive', () => {
    const messages = new SseParser().push(': ping\r\n\r\nevent: done\r\ndata: {}\r\n\r\n');

    expect(messages).toEqual([{ event: 'done', data: '{}' }]);
  });

  it('separe plusieurs messages arrives dans le meme fragment', () => {
    const messages = new SseParser().push(
      'event:delta\ndata:{"content":"a"}\n\nevent:delta\ndata:{"content":"b"}\n\n',
    );

    expect(messages.map((m) => m.data)).toEqual(['{"content":"a"}', '{"content":"b"}']);
  });
});
