package edu.jhu.thrax.hadoop.paraphrasing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Reducer;

import edu.jhu.thrax.hadoop.datatypes.RuleWritable;
import edu.jhu.thrax.hadoop.features.pivot.PivotedFeature;
import edu.jhu.thrax.hadoop.features.pivot.PivotedFeatureFactory;
import edu.jhu.thrax.util.FormatUtils;

public class PivotingReducer extends Reducer<RuleWritable, MapWritable, RuleWritable, MapWritable> {

  private static final Logger logger = Logger.getLogger(PivotingReducer.class.getName());

  private static enum PivotingCounters {
    F_READ, EF_READ, EF_PRUNED, EE_PRUNED, EE_WRITTEN
  };

  private static final Text EMPTY = new Text("");
  private static final DoubleWritable ZERO = new DoubleWritable(0.0);

  private Text currentSource;
  private Text currentLhs;

  private ArrayList<String> nts;
  private String lhs;

  private List<ParaphrasePattern> targets;
  private List<PivotedFeature> features;

  private Map<Text, PruningRule> translationPruningRules;
  private Map<Text, PruningRule> pivotedPruningRules;

  protected void setup(Context context) throws IOException, InterruptedException {
    currentLhs = null;
    currentSource = null;

    lhs = null;
    nts = new ArrayList<String>(2);

    targets = new ArrayList<ParaphrasePattern>();

    Configuration conf = context.getConfiguration();
    features = PivotedFeatureFactory.getAll(conf.get("thrax.features", ""));

    translationPruningRules = getTranslationPruningRules(conf.get("thrax.pruning", ""));
    pivotedPruningRules = getPivotedPruningRules(conf.get("thrax.pruning", ""));
  }

  protected void reduce(RuleWritable key, Iterable<MapWritable> values, Context context)
      throws IOException, InterruptedException {
    if (currentLhs == null || !(key.lhs.equals(currentLhs) && key.source.equals(currentSource))) {
      if (currentLhs == null) {
        currentLhs = new Text();
        currentSource = new Text();
      } else {
        pivotAll(context);
      }
      currentLhs.set(key.lhs);
      currentSource.set(key.source);

      if (currentLhs.getLength() == 0 || currentSource.getLength() == 0) return;

      try {
        lhs = FormatUtils.stripNonterminal(currentLhs.toString());
        nts = extractNonterminals(currentSource.toString());
      } catch (Exception e) {
        return;
      }

      targets.clear();
    }
    for (MapWritable value : values)
      if (!prune(value, translationPruningRules)) {
        try {
          ParaphrasePattern pp = new ParaphrasePattern(key.target.toString(), nts, lhs, value);
          targets.add(pp);
        } catch (Exception e) {
          context.getCounter(PivotingCounters.EF_PRUNED).increment(1);
        }
      } else {
        context.getCounter(PivotingCounters.EF_PRUNED).increment(1);
      }
  }

  protected void cleanup(Context context) throws IOException, InterruptedException {
    if (currentLhs != null) {
      pivotAll(context);
    }
  }

  protected void pivotAll(Context context) throws IOException, InterruptedException {
    context.getCounter(PivotingCounters.F_READ).increment(1);
    context.getCounter(PivotingCounters.EF_READ).increment(targets.size());

    for (int i = 0; i < targets.size(); i++) {
      for (int j = i; j < targets.size(); j++) {
        pivotOne(targets.get(i), targets.get(j), context);
        if (i != j) pivotOne(targets.get(j), targets.get(i), context);
      }
    }
  }

  protected void pivotOne(ParaphrasePattern src, ParaphrasePattern tgt, Context context)
      throws IOException, InterruptedException {
    RuleWritable pivoted_rule = new RuleWritable();
    MapWritable pivoted_features = new MapWritable();

    pivoted_rule.lhs = new Text(FormatUtils.markup(src.lhs));
    pivoted_rule.source = new Text(src.getMonotoneForm());
    pivoted_rule.target = new Text(tgt.getMatchingForm(src));

    pivoted_rule.featureLabel = EMPTY;
    pivoted_rule.featureScore = ZERO;

    try {
      // Compute the features.
      for (PivotedFeature f : features)
        pivoted_features.put(f.getFeatureLabel(), f.pivot(src.features, tgt.features));
    } catch (Exception e) {
      StringBuilder src_f = new StringBuilder();
      for (Writable w : src.features.keySet())
        src_f.append(w.toString() + "=" + src.features.get(w) + " ");
      StringBuilder tgt_f = new StringBuilder();
      for (Writable w : tgt.features.keySet())
        tgt_f.append(w.toString() + "=" + tgt.features.get(w) + " ");
      
      throw new RuntimeException(src.reordered + " \n " + tgt.reordered + " \n " +
          src_f.toString() + " \n " + tgt_f.toString() + " \n");
    }


    if (!prune(pivoted_features, pivotedPruningRules)) {
      context.write(pivoted_rule, pivoted_features);
      context.getCounter(PivotingCounters.EE_WRITTEN).increment(1);
    } else {
      context.getCounter(PivotingCounters.EE_PRUNED).increment(1);
    }
  }

  protected Map<Text, PruningRule> getPivotedPruningRules(String conf_string) {
    Map<Text, PruningRule> rules = new HashMap<Text, PruningRule>();
    String[] rule_strings = conf_string.split("\\s*,\\s*");
    for (String rule_string : rule_strings) {
      String[] f;
      boolean smaller;
      if (rule_string.contains("<")) {
        f = rule_string.split("<");
        smaller = true;
      } else if (rule_string.contains(">")) {
        f = rule_string.split(">");
        smaller = false;
      } else {
        continue;
      }
      Text label = PivotedFeatureFactory.get(f[0]).getFeatureLabel();
      rules.put(label, new PruningRule(smaller, Double.parseDouble(f[1])));
    }
    return rules;
  }

  protected Map<Text, PruningRule> getTranslationPruningRules(String conf_string) {
    Map<Text, PruningRule> rules = new HashMap<Text, PruningRule>();
    String[] rule_strings = conf_string.split("\\s*,\\s*");
    for (String rule_string : rule_strings) {
      String[] f;
      boolean smaller;
      if (rule_string.contains("<")) {
        f = rule_string.split("<");
        smaller = true;
      } else if (rule_string.contains(">")) {
        f = rule_string.split(">");
        smaller = false;
      } else {
        continue;
      }
      Double threshold = Double.parseDouble(f[1]);

      Set<Text> lower_bound_labels = PivotedFeatureFactory.get(f[0]).getLowerBoundLabels();
      if (lower_bound_labels != null) for (Text label : lower_bound_labels)
        rules.put(label, new PruningRule(smaller, threshold));

      Set<Text> upper_bound_labels = PivotedFeatureFactory.get(f[0]).getUpperBoundLabels();
      if (upper_bound_labels != null) for (Text label : upper_bound_labels)
        rules.put(label, new PruningRule(!smaller, threshold));
    }
    return rules;
  }

  protected boolean prune(MapWritable features, final Map<Text, PruningRule> rules) {
    for (Map.Entry<Text, PruningRule> e : rules.entrySet()) {
      if (features.containsKey(e.getKey())
          && e.getValue().applies((DoubleWritable) features.get(e.getKey()))) return true;
    }
    return false;
  }

  protected ArrayList<String> extractNonterminals(String source) {
    ArrayList<String> nts = new ArrayList<String>();
    String[] tokens = source.split("[\\s]+");

    // This assumes that the source side defines NT indexing, i.e. the first
    // NT encountered will have index 1 etc.
    for (String token : tokens) {
      if (FormatUtils.isNonterminal(token)) {
        nts.add(FormatUtils.stripNonterminal(token));
      }
    }
    return nts;
  }

  class ParaphrasePattern {

    private String[] tokens;

    private String monotone;
    private String reordered;

    int arity;
    private String lhs;
    private int[] positions;

    private MapWritable features;

    public ParaphrasePattern(String target, ArrayList<String> nts, String lhs, MapWritable features) {
      this.arity = nts.size();

      this.lhs = lhs;
      this.positions = new int[arity];
      this.features = new MapWritable(features);

      this.tokens = target.split("\\s+");

      buildForms();
    }

    private void buildForms() {
      StringBuilder m_builder = new StringBuilder();
      StringBuilder r_builder = new StringBuilder();

      int nt_count = 0;
      for (int i = 0; i < tokens.length; i++) {
        if (FormatUtils.isNonterminal(tokens[i])) {
          positions[nt_count] = i;
          nt_count++;
          String plain_nt = FormatUtils.stripIndexedNonterminal(tokens[i]);
          m_builder.append(FormatUtils.markup(plain_nt, nt_count));
          if (arity == 2) r_builder.append(FormatUtils.markup(plain_nt, 3 - nt_count));
        } else {
          m_builder.append(tokens[i]);
          r_builder.append(tokens[i]);
        }
        m_builder.append(" ");
        r_builder.append(" ");
      }
      monotone = m_builder.substring(0, m_builder.length() - 1);
      reordered = (arity == 2) ? r_builder.substring(0, r_builder.length() - 1) : monotone;
    }

    public String getMonotoneForm() {
      return monotone;
    }

    public String getMatchingForm(ParaphrasePattern src) {
      for (int i = 0; i < positions.length; i++) {
        if (!tokens[positions[i]].equals(src.tokens[src.positions[i]])) return reordered;
      }
      return monotone;
    }
  }

  class PruningRule {
    private boolean smaller;
    private double threshold;

    PruningRule(boolean smaller, double threshold) {
      this.smaller = smaller;
      this.threshold = threshold;
    }

    protected boolean applies(DoubleWritable value) {
      return (smaller ? value.get() < threshold : value.get() > threshold);
    }
  }
}
