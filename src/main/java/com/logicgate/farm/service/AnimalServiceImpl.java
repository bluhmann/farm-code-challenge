package com.logicgate.farm.service;

import com.logicgate.farm.domain.Animal;
import com.logicgate.farm.domain.Barn;
import com.logicgate.farm.domain.Color;
import com.logicgate.farm.factories.BarnFactory;
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
    List<Animal> existingAnimals = this.animalRepository.findByFavoriteColor(favoriteColor);
    if (existingAnimals.size() == 0) {
      // We have no animals yet with this favorite color, so create a barn and add the animal
      Barn newBarn = BarnFactory.createNewBarnForColor(favoriteColor, 0);
      animal.setBarn(newBarn);

      this.barnRepository.save(newBarn);
      return this.animalRepository.save(animal);
    }

    // This is the case we normally expect, we have at least one animal with this favorite color, so we need to work out
    // where the new animal will fit into the existing barns
    List<Barn> compatibleBarns = this.barnRepository.findByColorOrderByNameAsc(favoriteColor);
    if (this.extraBarnRequired(existingAnimals, compatibleBarns)) {
      // New barn and redistribution needed
      Barn newBarn = BarnFactory.createNewBarnForColor(favoriteColor, compatibleBarns.size());
      // Need to use the return value so the barns assigned to the animals have the ID field filled out
      newBarn = this.barnRepository.save(newBarn);
      compatibleBarns.add(newBarn);
      this.distributeAnimals(existingAnimals, compatibleBarns);

      // We now need to set the barn on the animal before finally saving and returning the persistened entity
      int barnIndex = this.getIndexOfBarnForAnimal(existingAnimals.size(), compatibleBarns.size());
      Barn animalBarn = compatibleBarns.get(barnIndex);
      animal.setBarn(animalBarn);
    } else {
      // Map the entries to a sorted list
      List<Map.Entry<Barn, Long>> sortedEntries = this.getSortedBarnCapacities(existingAnimals);

      // sortedEntries is now a list of Map.Entries, with the Key being the barn and value being the capacity of that
      // barn. Because we sorted it while creating it, the first entry will have the barn with the smallest capacity, so
      // we can grab that barn and assign it to our new animal.
      animal.setBarn(sortedEntries.get(0).getKey());
    }

    return this.animalRepository.save(animal);
  }

  @Override
  public void addToFarm(List<Animal> animals) {
    animals.forEach(this::addToFarm);
  }

  @Override
  public void removeFromFarm(Animal animal) {
    if (!this.animalRepository.existsById(animal.getId())) {
      // Do nothing if the animal doesn't exist
      return;
    }

    this.animalRepository.delete(animal);

    // We now need to deal with deleting barns and redistributing animals (if necessary)
    List<Animal> remainingAnimals = this.animalRepository.findByFavoriteColor(animal.getFavoriteColor());
    if (remainingAnimals.size() == 0) {
      // If this was the only animal in its barn, it's easy, delete the barn it was in
      this.barnRepository.delete(animal.getBarn());
    } else if (remainingAnimals.size() % 20 == 0) {
      // In this case, we have moved from having 21 to 20 animals (or 41 to 40, etc.), so we need to delete a barn
      // and redistribute the animals
      List<Barn> barns = this.barnRepository.findByColorOrderByNameDesc(animal.getFavoriteColor());
      Barn barnToDelete = barns.get(0);
      barns.remove(barnToDelete);
      this.distributeAnimals(remainingAnimals, barns);

      this.barnRepository.delete(barnToDelete); // Delete the most recently added barn
    } else {
      // In this scenario, the number of barns we have is fine, but we may have removed an animal from the barn with the
      // smallest capacity, if this is the case, we need to move an animal from another barn to barn that is now out of
      // sync with the rest of the barns (this is not a concern above as we are resitributing anyway, so everything will
      // be guaranteed to be synced after that)
      List<Map.Entry<Barn, Long>> sortedCapacities = this.getSortedBarnCapacities(remainingAnimals);
      Map.Entry<Barn, Long> smallestCapacityEntry = sortedCapacities.get(0);
      Map.Entry<Barn, Long> largestCapacityEntry = sortedCapacities.get(sortedCapacities.size() - 1);
      if (largestCapacityEntry.getValue() - smallestCapacityEntry.getValue() > 1) {
        Barn emptiestBarn = smallestCapacityEntry.getKey();
        Barn fullestBarn = largestCapacityEntry.getKey();

        Animal animalToMove = remainingAnimals
          .stream()
          .filter(a -> a.getBarn().equals(fullestBarn))
          .findFirst()
          .get(); // Calling get explicitly here as the list is created by counting barnIDs in the existingAnimals array

        animalToMove.setBarn(emptiestBarn);
        this.animalRepository.save(animalToMove);
      }
    }
  }

  @Override
  public void removeFromFarm(List<Animal> animals) {
    animals.forEach(animal -> removeFromFarm(animalRepository.getOne(animal.getId())));
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
      int barnIndex = this.getIndexOfBarnForAnimal(i, numOfBarns);

      Animal currentAnimal = animals.get(i);
      currentAnimal.setBarn(barns.get(barnIndex));
      this.animalRepository.save(currentAnimal);
    }
  }

  private int getIndexOfBarnForAnimal(int animalIndex, int numOfBarns) {
    return animalIndex % numOfBarns;
  }
}
