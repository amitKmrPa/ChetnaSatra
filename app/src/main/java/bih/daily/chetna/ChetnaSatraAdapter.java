package bih.daily.chetna;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import bih.daily.chetna.content.ChetnaSatraContent.Document;
import bih.daily.chetna.content.ChetnaSatraContent.Line;
import bih.daily.chetna.content.ChetnaSatraContent.Pair;
import bih.daily.chetna.content.ChetnaSatraContent.Para;
import bih.daily.chetna.content.ChetnaSatraContent.Qa;
import bih.daily.chetna.content.ChetnaSatraContent.Quote;
import bih.daily.chetna.content.ChetnaSatraContent.Section;
import bih.daily.chetna.content.ChetnaSatraContent.SubHeading;

/**
 * Renders a {@link Document} as a flat list of rows.
 *
 * <p>The document is a list of sections, each holding a list of blocks; flattening it into one row
 * list keeps a single {@link RecyclerView} responsible for the whole sheet, so regenerating the
 * content is a list swap rather than a view rebuild.
 */
class ChetnaSatraAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_SUBHEADING = 1;
    private static final int TYPE_QA = 2;
    private static final int TYPE_TEXT = 3;
    private static final int TYPE_PARA = 4;
    private static final int TYPE_QUOTE = 5;

    /** Row marker for a section title; content blocks are stored as themselves. */
    private static final class SectionTitle {
        final String text;

        SectionTitle(String text) {
            this.text = text;
        }
    }

    private final List<Object> rows = new ArrayList<>();

    void submit(Document document) {
        rows.clear();
        for (Section section : document.sections) {
            rows.add(new SectionTitle(section.title));
            rows.addAll(section.blocks);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        Object row = rows.get(position);
        if (row instanceof SectionTitle) return TYPE_SECTION;
        if (row instanceof SubHeading) return TYPE_SUBHEADING;
        if (row instanceof Qa) return TYPE_QA;
        if (row instanceof Para) return TYPE_PARA;
        if (row instanceof Quote) return TYPE_QUOTE;
        return TYPE_TEXT; // Pair and Line share one styled TextView
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_SECTION:
                return new SingleTextHolder(
                        inflater.inflate(R.layout.item_cs_section, parent, false), R.id.cs_section_title);
            case TYPE_SUBHEADING:
                return new SingleTextHolder(
                        inflater.inflate(R.layout.item_cs_subheading, parent, false), R.id.cs_subheading);
            case TYPE_QA:
                return new QaHolder(inflater.inflate(R.layout.item_cs_qa, parent, false));
            case TYPE_PARA:
                return new ParaHolder(inflater.inflate(R.layout.item_cs_para, parent, false));
            case TYPE_QUOTE:
                return new QuoteHolder(inflater.inflate(R.layout.item_cs_quote, parent, false));
            case TYPE_TEXT:
            default:
                return new SingleTextHolder(
                        inflater.inflate(R.layout.item_cs_text, parent, false), R.id.cs_text);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);

        if (row instanceof SectionTitle) {
            ((SingleTextHolder) holder).text.setText(((SectionTitle) row).text);

        } else if (row instanceof SubHeading) {
            ((SingleTextHolder) holder).text.setText(((SubHeading) row).text);

        } else if (row instanceof Qa) {
            Qa qa = (Qa) row;
            QaHolder qaHolder = (QaHolder) holder;
            qaHolder.question.setText(qa.number + ". " + qa.question);
            qaHolder.answer.setText(
                    qaHolder.answer.getContext().getString(R.string.cs_answer_prefix, qa.answer));

        } else if (row instanceof Para) {
            Para para = (Para) row;
            ParaHolder paraHolder = (ParaHolder) holder;
            if (para.heading == null) {
                paraHolder.heading.setVisibility(View.GONE);
            } else {
                paraHolder.heading.setVisibility(View.VISIBLE);
                paraHolder.heading.setText(para.heading);
            }
            paraHolder.body.setText(para.body);

        } else if (row instanceof Quote) {
            Quote quote = (Quote) row;
            QuoteHolder quoteHolder = (QuoteHolder) holder;
            quoteHolder.text.setText("\u201C" + quote.text + "\u201D");
            quoteHolder.author.setText("\u2014 " + quote.author);

        } else if (row instanceof Pair) {
            Pair pair = (Pair) row;
            String lead = (pair.number > 0 ? pair.number + ". " : "") + pair.term;
            ((SingleTextHolder) holder).text.setText(emphasise(lead, " \u2014 " + pair.meaning));

        } else if (row instanceof Line) {
            Line line = (Line) row;
            ((SingleTextHolder) holder).text.setText(emphasise(line.label, " " + line.value));
        }
    }

    /** Bold lead-in followed by plain remainder, e.g. "<b>नीलकंठ</b> — नीला है जो कंठ". */
    private static CharSequence emphasise(String bold, String plain) {
        SpannableStringBuilder sb = new SpannableStringBuilder(bold).append(plain);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, bold.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ── Holders ──────────────────────────────────────────────────────────────

    private static class SingleTextHolder extends RecyclerView.ViewHolder {
        final TextView text;

        SingleTextHolder(View itemView, int textId) {
            super(itemView);
            text = itemView.findViewById(textId);
        }
    }

    private static class QaHolder extends RecyclerView.ViewHolder {
        final TextView question;
        final TextView answer;

        QaHolder(View itemView) {
            super(itemView);
            question = itemView.findViewById(R.id.cs_question);
            answer = itemView.findViewById(R.id.cs_answer);
        }
    }

    private static class ParaHolder extends RecyclerView.ViewHolder {
        final TextView heading;
        final TextView body;

        ParaHolder(View itemView) {
            super(itemView);
            heading = itemView.findViewById(R.id.cs_para_heading);
            body = itemView.findViewById(R.id.cs_para_body);
        }
    }

    private static class QuoteHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final TextView author;

        QuoteHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.cs_quote_text);
            author = itemView.findViewById(R.id.cs_quote_author);
        }
    }
}
