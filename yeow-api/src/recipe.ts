import { post } from './task.js';
import type { TaskOptions } from './task.js';
import type { ItemStack } from './item.js';

type RecipeDefinition = ShapedRecipe | ShapelessRecipe | FurnaceRecipe | BlastRecipe | SmokerRecipe | CampfireRecipe;

interface ShapedRecipe {
  type: 'shaped';
  key: string;
  result: ItemStack;
  shape: string[];
  ingredients: Record<string, string>;
  group?: string;
}

interface ShapelessRecipe {
  type: 'shapeless';
  key: string;
  result: ItemStack;
  ingredients: (string | ItemStack)[];
  group?: string;
}

interface FurnaceRecipe {
  type: 'furnace';
  key: string;
  input: string;
  result: ItemStack;
  experience?: number;
  cookingTime?: number;
}
interface BlastRecipe {
  type: 'blast';
  key: string;
  input: string;
  result: ItemStack;
  experience?: number;
  cookingTime?: number;
}
interface SmokerRecipe {
  type: 'smoker';
  key: string;
  input: string;
  result: ItemStack;
  experience?: number;
  cookingTime?: number;
}
interface CampfireRecipe {
  type: 'campfire';
  key: string;
  input: string;
  result: ItemStack;
  experience?: number;
  cookingTime?: number;
}

export function add(recipe: RecipeDefinition, options?: TaskOptions): Promise<boolean> {
  return post<boolean>('recipe.add', recipe as unknown as Record<string, unknown>, options);
}

export function remove(key: string, options?: TaskOptions): Promise<void> {
  return post('recipe.remove', { key }, options);
}

export function getForItem(item: ItemStack, options?: TaskOptions): Promise<string[]> {
  return post<string[]>('recipe.getForItem', { item }, options);
}
