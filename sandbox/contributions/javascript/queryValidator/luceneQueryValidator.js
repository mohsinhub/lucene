//
// JavaScript Lucene Query Validator
// Author: Kelvin Tan  (kelvin@relevanz.com)
// Date:   10/04/2002

// validates a lucene query.
// @param Form field that contains the query
function doCheckLuceneQuery(queryField)
{
  var query = queryField.value;
  if(query != null && query.length > 0)
  {
    var matches = query.match(/^(\*)|([^a-zA-Z0-9_]\*)/);

    // check wildcards are used properly
    if(matches != null && matches.length > 0)
    {
      alert("Invalid search query! The wildcard (*) character must be preceded by at least one alphabet or number. Please try again.")
      return false;
    }

    // check parentheses are used properly
      matches = query.match(/^([^\n()]*|(\(([a-zA-Z0-9_+\-:()\" ]|\*)+\)))*$/);
    if(matches == null || matches.length == 0)
    {
      alert("Invalid search query! Parentheses must contain at least one alphabet or number. Please try again.")
      return false;
    }

    // check '+' and '-' are used properly      
    matches = query.match(/^(([^\n+-]*|[+-]([a-zA-Z0-9_:()]|\*)+))*$/);
    if(matches == null || matches.length == 0)
    {
      alert("Invalid search query! '+' and '-' modifiers must be followed by at least one alphabet or number. Please try again.")
      return false;
    }      

    // check that quote marks are closed
    matches = query.match(/\"/g);
    if(matches != null)
    {
      var number = matches.length;
      if((number % 2) > 0)
      {
        alert("Invalid search query! Please close all quote (\") marks.");
        return false;
      }
    }

    // check ':' is used properly
      matches = query.match(/^(([^\n:]*|([a-zA-Z0-9_]|\*)+[:]([a-zA-Z0-9_()"]|\*)+))*$/);
    if(matches == null || matches.length == 0)
    {
      alert("Invalid search query! Field declarations (:) must be preceded by at least one alphabet or number and followed by at least one alphabet or number. Please try again.")
      return false;
    }

    return true;
  }
}
