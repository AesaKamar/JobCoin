# JobCoin


## Project Structure
The project is divided into several modules, separating out the different concerns of the applciation.
All code aims to obe purely functional and maintiain referential transparency. 

### A note on comments and style
I generally discourage myself frow writing comments in the code, as I feel it can lead me to more easily justify writing less-than-easily-readable code. Hopefully, choices in variable naming and strong type signatures should be able to self-document in an enforceable way. Only public contract-level items are documented, as per guidelines of `ScalaDoc`. 
 
Code is formatted using `scalafmt`

### Library choices
`Monix`: Useful foor reasoning about Asynchronous computations in a referentially transparent way.
 
`sttp`: Barebones HTTP client library with pluggable backends. Not very opinionated, very flexible. 
 
`circe`: Minimal boilerplate JSON encoding/decoding library. 

## Running tests
`sbt test`


## Running integration tests
`sbt it:test`
