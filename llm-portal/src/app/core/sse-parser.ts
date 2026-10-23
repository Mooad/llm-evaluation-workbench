export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Decoupe un flux text/event-stream en messages.
 *
 * Fait a la main parce qu'EventSource ne sait faire que du GET, alors que /chat/stream
 * est un POST (l'historique part dans le corps). Les fragments reseau ne respectent
 * pas les frontieres de messages : on accumule jusqu'a la ligne vide qui termine
 * chaque message.
 */
export class SseParser {
  private buffer = '';
  private event = 'message';
  private data: string[] = [];

  /** Ajoute un fragment recu et renvoie les messages devenus complets. */
  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const messages: SseMessage[] = [];

    let newline: number;
    while ((newline = this.buffer.search(/\r\n|\n|\r/)) >= 0) {
      const line = this.buffer.slice(0, newline);
      const separatorLength = this.buffer.startsWith('\r\n', newline) ? 2 : 1;
      this.buffer = this.buffer.slice(newline + separatorLength);

      if (line === '') {
        if (this.data.length > 0) {
          messages.push({ event: this.event, data: this.data.join('\n') });
        }
        this.event = 'message';
        this.data = [];
      } else if (!line.startsWith(':')) {
        this.readField(line);
      }
    }
    return messages;
  }

  private readField(line: string): void {
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    // Spring ecrit "data:{...}" sans espace, la norme autorise "data: {...}" : on accepte les deux.
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      this.event = value;
    } else if (field === 'data') {
      this.data.push(value);
    }
  }
}
