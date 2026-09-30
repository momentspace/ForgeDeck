package dev.forgedeck.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser
import java.net.URI

data class MarkdownPart(val text:AnnotatedString,val heading:Int=0,val code:Boolean=false,val divider:Boolean=false)
fun markdownParts(source:String):List<MarkdownPart> {
    val parts=mutableListOf<MarkdownPart>()
    fun inline(node:Node,b:AnnotatedString.Builder) {
        when(node) {
            is MarkdownTextNode -> b.append(node.literal)
            is SoftLineBreak,is HardLineBreak -> b.append("\n")
            is Code -> { b.pushStyle(SpanStyle(fontFamily=FontFamily.Monospace));b.append(node.literal);b.pop() }
            is StrongEmphasis,is Emphasis -> {
                b.pushStyle(if(node is StrongEmphasis)SpanStyle(fontWeight=FontWeight.Bold)else SpanStyle(fontStyle=FontStyle.Italic))
                var child=node.firstChild;while(child!=null){inline(child,b);child=child.next};b.pop()
            }
            is Link -> {
                val valid=runCatching{URI(node.destination).let{it.scheme=="https" && !it.host.isNullOrBlank() && it.userInfo==null}}.getOrDefault(false)
                if(valid){b.pushStringAnnotation("URL",node.destination);b.pushStyle(SpanStyle(textDecoration=TextDecoration.Underline))}
                var child=node.firstChild;while(child!=null){inline(child,b);child=child.next}
                if(valid){b.pop();b.pop()}
            }
            is Image -> { b.append("[画像: ");var child=node.firstChild;while(child!=null){inline(child,b);child=child.next};b.append("]") }
            is HtmlInline -> b.append(node.literal)
            else -> {var child=node.firstChild;while(child!=null){inline(child,b);child=child.next}}
        }
    }
    fun blocks(node:Node,prefix:String="") {
        when(node) {
            is Paragraph,is Heading -> parts.add(MarkdownPart(buildAnnotatedString{append(prefix);inline(node,this)},heading=if(node is Heading)node.level else 0))
            is FencedCodeBlock -> parts.add(MarkdownPart(AnnotatedString(node.literal.trimEnd()),code=true))
            is IndentedCodeBlock -> parts.add(MarkdownPart(AnnotatedString(node.literal.trimEnd()),code=true))
            is HtmlBlock -> parts.add(MarkdownPart(AnnotatedString(node.literal),code=true))
            is ThematicBreak -> parts.add(MarkdownPart(AnnotatedString(""),divider=true))
            is BulletList,is OrderedList -> {
                var child=node.firstChild;var number=if(node is OrderedList)node.startNumber else 0
                while(child!=null){blocks(child,prefix+if(node is OrderedList)"${number++}. "else"• ");child=child.next}
            }
            is BlockQuote -> {var child=node.firstChild;while(child!=null){blocks(child,"│ $prefix");child=child.next}}
            else -> {var child=node.firstChild;while(child!=null){blocks(child,prefix);child=child.next}}
        }
    }
    blocks(Parser.builder().build().parse(source));return parts
}
/** Native text only. No HTML execution, remote images or hidden network fetches. */
@Composable fun MarkdownBody(source:String,web:(String)->Unit) {
    val parts by produceState<List<MarkdownPart>?>(null,source){value=withContext(Dispatchers.Default){markdownParts(source)}}
    Column(Modifier.fillMaxWidth()) {
        if(parts==null)Text("本文を準備しています…")
        parts?.forEach { part ->
            if(part.divider)HorizontalDivider(Modifier.padding(vertical=8.dp))
            else if(part.code)SelectionContainer{Text(part.text,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(12.dp),fontFamily=FontFamily.Monospace)}
            else {
                val style=when(part.heading){1->MaterialTheme.typography.headlineSmall;2->MaterialTheme.typography.titleLarge;3,4,5,6->MaterialTheme.typography.titleMedium;else->MaterialTheme.typography.bodyLarge}.copy(color=MaterialTheme.colorScheme.onSurface)
                SelectionContainer{ClickableText(part.text,Modifier.padding(vertical=6.dp),style=style,onClick={offset->part.text.getStringAnnotations("URL",offset,offset).firstOrNull()?.let{web(it.item)}})}
            }
        }
    }
}
