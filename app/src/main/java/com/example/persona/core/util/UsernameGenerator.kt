package com.example.persona.core.util

import kotlin.random.Random

object UsernameGenerator {

    private val adjectives = listOf(
        "Agile", "Ancient", "Brave", "Bright", "Bronze", "Calm", "Clever", "Cool", "Crimson",
        "Crystal", "Cyber", "Dandy", "Dapper", "Daring", "Dark", "Dauntless", "Deft", "Delicate",
        "Diamond", "Digital", "Electric", "Elegant", "Emerald", "Enchanted", "Epic", "Eternal",
        "Fancy", "Fearless", "Fire", "First", "Flying", "Free", "Frost", "Gallant", "Gentle",
        "Ghost", "Giant", "Giga", "Gilded", "Glass", "Golden", "Grand", "Grave", "Great",
        "Green", "Gritty", "Hardy", "Humble", "Hyper", "Ice", "Iron", "Jade", "Jolly",
        "Jumping", "Junior", "Just", "Keen", "Last", "Lazy", "Light", "Lightning", "Little",
        "Living", "Lone", "Long", "Lucky", "Magic", "Mega", "Merry", "Metal", "Mighty",
        "Mini", "Misty", "Moon", "Mythic", "Nano", "New", "Night", "Noble", "Old",
        "Omega", "Onyx", "Perfect", "Pixel", "Proud", "Pyro", "Quick", "Quiet", "Rain",
        "Rapid", "Rare", "Regal", "Retro", "Rising", "River", "Robo", "Rough", "Royal",
        "Ruby", "Rune", "Sapphire", "Savage", "Scarlet", "Secret", "Shadow", "Shining", "Silent",
        "Silver", "Sky", "Sleeping", "Small", "Solar", "Solid", "Space", "Star", "Steel",
        "Stone", "Storm", "Stray", "Strong", "Summer", "Sun", "Super", "Swift", "Terra",
        "Thunder", "Time", "Tiny", "Ultra", "Valiant", "Vivid", "Void", "Wandering", "Warp",
        "Water", "White", "Wild", "Winter", "Wise", "Wonder"
    )

    private val nouns = listOf(
        "Albatross", "Alligator", "Alpaca", "Anaconda", "Ant", "Antelope", "Ape", "Aphid", "Arachnid",
        "ArcticFox", "Armadillo", "Arrow", "Automaton", "Avatar", "Avocado", "Axolotl", "Badger", "Bandit",
        "Banshee", "Barracuda", "Basilisk", "Bass", "Bat", "Beacon", "Bear", "Beaver", "Bee", "Beetle",
        "Bison", "Blade", "Blaze", "Bobcat", "Bolt", "Bonobo", "Bonsai", "Bot", "Boulder", "Buffalo",
        "Butterfly", "Buzzard", "Caiman", "Camel", "Capuchin", "Capybara", "Caracal", "Cassowary", "Cat",
        "Centaur", "Centipede", "Chameleon", "Chamois", "Cheetah", "Cherry", "Chihuahua", "Chimera", "Chimp",
        "Chipmunk", "Cobra", "Comet", "Condor", "Corgi", "Cougar", "Coyote", "Crab", "Crane", "Cricket",
        "Crocodile", "Crow", "Crystal", "Dagger", "Dalmatian", "Deer", "Demon", "Dingo", "Dinosaur",
        "Djinn", "Dodger", "Dog", "Dolphin", "Donkey", "Dragon", "Dreamer", "Drifter", "Droid", "Drone",
        "Druid", "Duck", "Dunker", "Eagle", "Echidna", "Eel", "Egret", "Elephant", "Elk", "Emu",
        "Enigma", "Explorer", "Falcon", "Fang", "Ferret", "Finch", "Firefly", "Fish", "Flamingo", "Flea",
        "Flicker", "Flounder", "Flower", "Flux", "Fly", "Fox", "Frog", "Gadget", "Galleon", "Gazelle",
        "Gecko", "Gem", "Genie", "Ghost", "Ghoul", "Gibbon", "Giraffe", "Glider", "Gnu", "Goat", "Goblin",
        "Goose", "Gopher", "Gorilla", "Grasshopper", "Gremlin", "Grizzly", "Gryphon", "Guppy", "Hacker",
        "Halcyon", "Hammerhead", "Hamster", "Harrier", "Hawk", "Hedgehog", "Heron", "Hippo", "Hornet",
        "Horse", "Hound", "Hummingbird", "Hunter", "Hydra", "Hyena", "Ibis", "Iguana", "Impala", "Inferno",

        "Jackal", "Jaguar", "Javelin", "Jay", "Jellyfish", "Jerboa", "Juggernaut", "Jumper", "Kangaroo",
        "Kingfisher", "Kite", "Kitten", "Kiwi", "Knight", "Koala", "Kraken", "Ladybug", "Lama", "Lamprey",
        "Lark", "Lemming", "Lemon", "Lemur", "Leopard", "Leviathan", "Liger", "Lion", "Lizard", "Llama",
        "Lobster", "Locust", "Loris", "Lynx", "Macaw", "Mage", "Magpie", "Mamba", "Mammoth", "Manatee",
        "Mandrake", "Mantis", "Marlin", "Marmoset", "Meerkat", "Meteor", "Millipede", "Mole", "Mongoose",
        "Monk", "Monkey", "Moose", "Mosquito", "Moth", "Mouse", "Mule", "Narwhal", "Nebula", "Nemesis",
        "Newt", "Nexus", "Ninja", "Nymph", "Ocelot", "Octopus", "Okapi", "Opossum", "Oracle", "Orca",
//        "Oryx", "Ostrich", "Otter", "Owl", "Ox", "Paladin", "Panther", "Papaya", "Paradox", "Parakeet",
        "Parrot", "Pathfinder", "Penguin", "Phantom", "Phoenix", "Piranha", "Pixie", "Platypus", "Pony",
        "Porcupine", "Porpoise", "Possum", "Potato", "Prawn", "Prophet", "Pug", "Puma", "Quasar", "Rabbit",
        "Raccoon", "Ranger", "Rat", "Raven", "Ray", "Reaper", "Rebel", "RedPanda", "Reindeer", "Revenant",
        "Rhino", "Rider", "Ringer", "Rival", "Robin", "Rodent", "Rogue", "Rook", "Rooster", "Rover",
        "Salamander", "Samurai", "Savage", "Scorpion", "Scout", "Seagull", "Seahorse", "Seal", "Seeker",
        "Sentinel", "Serpent", "Serval", "Shadow", "Shark", "Sheep", "Shogun", "Shrimp", "Sidewinder",
        "Simian", "Skink", "Skunk", "Sloth", "Snail", "Snake", "Sniper", "Snowman", "Sorcerer", "Sparrow",
        "Specter", "Sphinx", "Spider", "Sprite", "Squid", "Squirrel", "Starling", "Stingray", "Stork",
        "Storm", "Surfer", "Swallow", "Swan", "Swordfish", "Tarantula", "Tarsier", "Termite", "Terror",
        "Thor", "Thrasher", "Tiger", "Toad", "Tornado", "Tortoise", "Toucan", "Traveler", "Troll",
        "Turtle", "Unicorn", "Vagabond", "Vampire", "Vandal", "Viper", "Voyager", "Vulture", "Wallaby",
        "Walrus", "Wanderer", "Warlock", "Warrior", "Warthog", "Wasp", "Weasel", "Whale", "Wildcat",
        "Wolf", "Wolverine", "Wombat", "Woodpecker", "Worm", "Wraith", "Wren", "Yak", "Yeti", "Zebra"
    )

    fun generate(): String {
        val randomAdjective = adjectives.random()
        val randomNoun = nouns.random()
        return "$randomAdjective$randomNoun"
    }
}
