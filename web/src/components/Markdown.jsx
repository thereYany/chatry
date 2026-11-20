import { useMemo } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

/** 助手回复的 Markdown 渲染（先转 HTML 再消毒，避免 XSS）。 */
export default function Markdown({ content }) {
  const html = useMemo(() => {
    const raw = marked.parse(content || '', { breaks: true, gfm: true });
    return DOMPurify.sanitize(raw);
  }, [content]);
  return <div className="md" dangerouslySetInnerHTML={{ __html: html }} />;
}
