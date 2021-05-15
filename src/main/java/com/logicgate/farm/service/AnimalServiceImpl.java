package com.logicgate.farm.service;

import com.logicgate.farm.domain.Animal;
import com.logicgate.farm.domain.Barn;
import com.logicgate.farm.domain.Color;
import com.logicgate.farm.repository.AnimalRepository;
import com.logicgate.farm.repository.BarnRepository;

import com.logicgate.farm.util.FarmUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class AnimalServiceImpl implements AnimalService {

  private final AnimalRepository animalRepository;

  private final BarnRepository barnRepository;

  @Autowired
  public AnimalServiceImpl(AnimalRepository animalRepository, BarnRepository barnRepository) {
    this.animalRepository = animalRepository;
    this.barnRepository = barnRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Animal> findAll() {
    return animalRepository.findAll();
  }

  @Override
  public void deleteAll() {
    animalRepository.deleteAll();
  }

  @Override
  public Animal addToFarm(Animal animal) {
    Color favoriteColor = animal.getFavoriteColor();
    List<Barn> compatibleBarns = this.barnRepository.findByColorOrderByNameAsc(favoriteColor);
    if (compatibleBarns.size() == 0) {
      // We have no barns yet with this favorite color, so create a barn and add the animal
      Barn newBarn = new Barn(String.format("%s-%d", favoriteColor.toString(), 0), favoriteColor);
      animal.setBarn(newBarn);

      this.barnRepository.save(newBarn);
      return this.animalRepository.save(animal);
    }

    // This is the case we normally expect, we have at least one barn with this animal's favorite color, so we need to
    // work out which barn this animal will be placed in
    List<Animal> existingAnimals = this.animalRepository.findByFavoriteColor(favoriteColor);
    if (this.extraBarnRequired(existingAnimals, compatibleBarns)) {
      // New barn and redistribution needed
      System.out.println("bigger");
      Barn newBarn = new Barn(String.format("%s-%d", favoriteColor.toString(), compatibleBarns.size()), favoriteColor);
      compatibleBarns.add(newBarn);
      this.distributeAnimals(existingAnimals, compatibleBarns);
      this.barnRepository.save(newBarn);

      int barnIndex = existingAnimals.size() % compatibleBarns.size();
      Barn animalBarn = compatibleBarns.get(barnIndex);
      animal.setBarn(animalBarn);
      return this.animalRepository.save(animal);
    } else {
      // Map the entries to a sorted list
      List<Map.Entry<Barn, Long>> sortedEntries = this.getSortedBarnCapacities(existingAnimals);

      // sortedEntries is now a list of our Map.Entries, with the Key being the barn and value being the current
      // capacity. Because we sorted it while creating it, the first barn will have the smallest capacity, so we can
      // grab that barn and assign it to our new animal.
      animal.setBarn(sortedEntries.get(0).getKey());
      return this.animalRepository.save(animal);
    }
  }

  private List<Map.Entry<Barn, Long>> getSortedBarnCapacities(List<Animal> animals) {
    Map<Barn, Long> barnCapacities =
      animals
        .stream()
        .collect(
          Collectors.groupingBy(
            (a) -> a.getBarn(),
            Collectors.counting()
          )
        );
    // Map the entries to a sorted list
    return barnCapacities
      .entrySet()
      .stream()
      .sorted(Map.Entry.comparingByValue())
      .collect(Collectors.toList());
  }

  private boolean extraBarnRequired(List<Animal> animals, List<Barn> barns) {
    int numOfAnimals = animals.size();
    int barnCapacity = FarmUtils.barnCapacity();

    return ((numOfAnimals / barnCapacity) + 1) > barns.size();
  }

  private void distributeAnimals(List<Animal> animals, List<Barn> barns) {
    int numOfBarns = barns.size();
    for (int i = 0; i < animals.size(); i++) {
      int barnIndex = i % numOfBarns;

      Animal currentAnimal = animals.get(i);
      currentAnimal.setBarn(barns.get(barnIndex));
      this.animalRepository.save(currentAnimal);
    }
  }

//  private Barn getNextBarn(Map<Object, Long>)

  @Override
  public void addToFarm(List<Animal> animals) {
    animals.forEach(this::addToFarm);
  }

  @Override
  public void removeFromFarm(Animal animal) {
    // TODO: implementation of this method
  }

  @Override
  public void removeFromFarm(List<Animal> animals) {
    animals.forEach(animal -> removeFromFarm(animalRepository.getOne(animal.getId())));
  }
}
