package com.logicgate.farm;

import com.logicgate.farm.domain.Animal;
import com.logicgate.farm.domain.Barn;
import com.logicgate.farm.domain.Color;
import com.logicgate.farm.repository.BarnRepository;
import com.logicgate.farm.service.AnimalService;
import com.logicgate.farm.util.FarmUtils;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

@SpringBootTest
@RunWith(SpringRunner.class)
public class ApplicationTest {

  private static final int ANIMAL_SEED = 1000;

  @Autowired
  private BarnRepository barnRepository;

  @Autowired
  private AnimalService animalService;

  @After
  public void tearDown() {
    animalService.deleteAll();
    barnRepository.deleteAll();
  }

  @Test
  public void addAnimalsToFarm() {
    animalService.addToFarm(IntStream.range(0, ANIMAL_SEED)
      .mapToObj(value -> new Animal(FarmUtils.animalName(value), FarmUtils.randomColor()))
      .collect(Collectors.toList()));

    checkAnimals(ANIMAL_SEED);
  }

  @Test
  public void addAnimalsToFarm_partial() {
    int num = 90;
    animalService.addToFarm(IntStream.range(0, num)
      .mapToObj(value -> new Animal(FarmUtils.animalName(value), Color.RED))
      .collect(Collectors.toList()));

    checkAnimals(num);
  }

  @Test
  public void removeAnimalsFromFarm() {
    animalService.addToFarm(IntStream.range(0, ANIMAL_SEED)
        .mapToObj(value -> new Animal(FarmUtils.animalName(value), FarmUtils.randomColor()))
        .collect(Collectors.toList()));

    List<Animal> animals = animalService.findAll();
    List<Animal> animalsToRemove = animals.stream()
        .filter(animal -> ThreadLocalRandom.current().nextBoolean())
        .collect(Collectors.toList());

    animalService.removeFromFarm(animalsToRemove);

    checkAnimals(animals.size() - animalsToRemove.size());
  }

  private void checkAnimals(int expected) {
    List<Animal> animalResult = animalService.findAll();
    assertThat("Animal updates should reflect in persisted entities.", animalResult.size(), is(expected));

    Map<Barn, List<Animal>> barnAnimalMap = animalResult.stream()
        .collect(Collectors.groupingBy(Animal::getBarn));

    barnAnimalMap.forEach((barn, animals) -> {
      assertThat("Barns should not exceed capacity.", barn.getCapacity(), greaterThanOrEqualTo(animals.size()));
      assertThat("Animals should match the barn color.",
          animals.stream().anyMatch(animal -> animal.getFavoriteColor() != barn.getColor()), is(false));
    });

    // no unused barns
    assertThat("No barns should be empty.", barnRepository.count(), is((long) barnAnimalMap.keySet().size()));

    Map<Color, List<Barn>> colorBarnMap = barnAnimalMap.keySet().stream()
        .collect(Collectors.groupingBy(Barn::getColor));

    colorBarnMap.forEach((color, barns) -> {
      Integer minCapacity = barns.stream()
          .mapToInt(Barn::getCapacity).min()
          .orElse(FarmUtils.barnCapacity());

      List<Integer> unusedCapacity = barns.stream()
          .map(barn -> barn.getCapacity() - barnAnimalMap.get(barn).size())
          .collect(Collectors.toList());

      Integer totalUnusedCapacity = unusedCapacity.stream()
          .mapToInt(i -> i)
          .sum();

      assertThat("Optimal barns should exist for capacity requirements.",
          minCapacity, greaterThan(totalUnusedCapacity));
      assertThat("Animal distribution should maximize free barn space.",
          Collections.max(unusedCapacity) - Collections.min(unusedCapacity), lessThanOrEqualTo(1));
    });
  }

}
