package edu.jhu.thrax.hadoop.features.pivot;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;

public class PivotedRarityPenaltyFeature implements PivotedFeature {

  private static final Text LABEL = new Text("RarityPenalty");

  private static final DoubleWritable ZERO = new DoubleWritable(0.0);
  
  private static final double RENORMALIZE = Math.exp(-1);

  private double aggregated_rp;

  public String getName() {
    return "rarity";
  }

  public Text getFeatureLabel() {
    return LABEL;
  }

  public Set<String> getPrerequisites() {
    Set<String> prereqs = new HashSet<String>();
    prereqs.add("rarity");
    return prereqs;
  }

  public DoubleWritable pivot(MapWritable a, MapWritable b) {
    double a_rp = ((DoubleWritable) a.get(new Text("RarityPenalty"))).get();
    double b_rp = ((DoubleWritable) b.get(new Text("RarityPenalty"))).get();
    return new DoubleWritable(Math.max(a_rp, b_rp));
  }

  public void unaryGlueRuleScore(Text nt, Map<Text, Writable> map) {
    map.put(LABEL, ZERO);
  }

  public void binaryGlueRuleScore(Text nt, Map<Text, Writable> map) {
    map.put(LABEL, ZERO);
  }

  public void initializeAggregation() {
    aggregated_rp = -1;
  }

  public void aggregate(MapWritable a) {
    double rp = ((DoubleWritable) a.get(LABEL)).get();
    if (aggregated_rp == -1) {
      aggregated_rp = rp;
    } else {
      // Rarity is exp(1 - count). To compute rarity over a sum of counts:
      // rarity_{1+2} = exp(1 - (count_1 + count_2)) = exp(1 - count_1) * exp(-count_2) = 
      //     = exp(1 - count_1) * exp(1 - count_2) * exp(-1) = rarity_1 * rarity_2 * exp(-1) 
      aggregated_rp *= rp * RENORMALIZE;
    }
  }

  public DoubleWritable finalizeAggregation() {
    return new DoubleWritable(aggregated_rp);
  }

  @Override
  public Set<Text> getLowerBoundLabels() {
    Set<Text> lower_bound_labels = new HashSet<Text>();
    lower_bound_labels.add(new Text("RarityPenalty"));
    return lower_bound_labels;
  }

  @Override
  public Set<Text> getUpperBoundLabels() {
    return null;
  }
}
