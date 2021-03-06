// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "elementiterator.h"
#include <vespa/searchcommon/attribute/i_search_context.h>
#include <vespa/searchlib/fef/termfieldmatchdata.h>

using search::fef::TermFieldMatchDataPosition;

namespace search::attribute {

void
ElementIterator::doSeek(uint32_t docid) {
    _search->doSeek(docid);
    setDocId(_search->getDocId());
}

void
ElementIterator::doUnpack(uint32_t docid) {
    _tfmd.reset(docid);
    int32_t weight(0);
    for (int32_t id = _searchContext.find(docid, 0, weight); id >= 0; id = _searchContext.find(docid, id+1, weight)) {
        _tfmd.appendPosition(TermFieldMatchDataPosition(id, 0, weight, 1));
    }
}

vespalib::Trinary
ElementIterator::is_strict() const {
    return _search->is_strict();
}

void
ElementIterator::initRange(uint32_t beginid, uint32_t endid) {
    SearchIterator::initRange(beginid, endid);
    _search->initRange(beginid, endid);
    setDocId(_search->getDocId());
}

ElementIterator::ElementIterator(SearchIterator::UP search, const ISearchContext & sc, fef::TermFieldMatchData & tfmd)
    : _search(std::move(search)),
      _searchContext(sc),
      _tfmd(tfmd)
{
}

ElementIterator::~ElementIterator() = default;

}
