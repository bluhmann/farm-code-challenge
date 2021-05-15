package com.logicgate.farm.factories;

import com.logicgate.farm.domain.Barn;
import com.logicgate.farm.domain.Color;

public class BarnFactory {

  public static Barn createNewBarnForColor(Color color, int barnNumber) {
    return new Barn(String.format("%s-%d", color.toString(), barnNumber), color);
  }

}
